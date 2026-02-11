package org.session.libsession.messaging.sending_receiving.pollers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.messages.Message.Companion.senderOrSync
import org.session.libsession.messaging.sending_receiving.MessageParser
import org.session.libsession.messaging.sending_receiving.ReceivedMessageProcessor
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.AlterTtlApi
import org.thoughtcrime.securesms.api.snode.RetrieveMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.api.swarm.SwarmSnodeSelector
import org.thoughtcrime.securesms.api.swarm.execute
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import kotlin.time.Duration.Companion.days

class Poller @AssistedInject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val lokiApiDatabase: LokiAPIDatabaseProtocol,
    private val prefs: PreferenceStorage,
    networkConnectivity: NetworkConnectivity,
    private val snodeClock: SnodeClock,
    private val receivedMessageHashDatabase: ReceivedMessageHashDatabase,
    private val processor: ReceivedMessageProcessor,
    private val messageParser: MessageParser,
    private val retrieveMessageFactory: RetrieveMessageApi.Factory,
    private val alterTtlApiFactory: AlterTtlApi.Factory,
    private val swarmApiExecutor: SwarmApiExecutor,
    private val swarmSnodeSelector: SwarmSnodeSelector,
    appVisibilityManager: AppVisibilityManager,
    @Assisted scope: CoroutineScope
) : BasePoller<Unit>(
    networkConnectivity = networkConnectivity,
    scope = scope,
    appVisibilityManager = appVisibilityManager
) {
    private val userPublicKey: String
        get() = storage.getUserPublicKey().orEmpty()

    companion object {
        private val hasMigratedToMultiPartConfigKey = PreferenceKey.boolean("migrated_to_multi_part_config")
    }


    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): Poller
    }

    override suspend fun doPollOnce(isFirstPollSinceAppStarted: Boolean) {
        // Migrate to multipart config when needed
        if (isFirstPollSinceAppStarted && !prefs[hasMigratedToMultiPartConfigKey]) {
            val allConfigNamespaces = intArrayOf(Namespace.USER_PROFILE(),
                Namespace.USER_GROUPS(),
                Namespace.CONTACTS(),
                Namespace.CONVO_INFO_VOLATILE(),
                Namespace.GROUP_KEYS(),
                Namespace.GROUP_INFO(),
                Namespace.GROUP_MEMBERS()
            )
            // To migrate to multi part config, we'll need to fetch all the config messages so we
            // get the chance to process those multipart messages again...
            lokiApiDatabase.clearLastMessageHashesByNamespaces(*allConfigNamespaces)
            receivedMessageHashDatabase.removeAllByNamespaces(*allConfigNamespaces)

            prefs[hasMigratedToMultiPartConfigKey] = true
        }

        // When we are only just starting to set up the account, we want to poll only the user
        // profile config so the user can see their name/avatar ASAP. Once this is done, we
        // will do a full poll immediately.
        val pollOnlyUserProfileConfig = isFirstPollSinceAppStarted &&
                configFactory.withUserConfigs { it.userProfile.activeHashes().isEmpty() }

        poll(
            snode = swarmSnodeSelector.selectSnode(userPublicKey),
            pollOnlyUserProfileConfig = pollOnlyUserProfileConfig
        )
    }

    // region Private API
    private fun processPersonalMessages(messages: List<RetrieveMessageResponse.Message>) {
        if (messages.isEmpty()) {
            Log.d(logTag, "No personal messages to process")
            return
        }

        Log.d(logTag, "Received ${messages.size} personal messages from snode")

        processor.startProcessing("Poller") { ctx ->
            for (message in messages) {
                if (receivedMessageHashDatabase.checkOrUpdateDuplicateState(
                        swarmPublicKey = userPublicKey,
                        namespace = Namespace.DEFAULT(),
                        hash = message.hash
                    )) {
                    Log.d(logTag, "Skipping duplicated message ${message.hash}")
                    continue
                }

                try {
                    val result = messageParser.parse1o1Message(
                        data = message.data,
                        serverHash = message.hash,
                        currentUserEd25519PrivKey = ctx.currentUserEd25519KeyPair.secretKey.data,
                        currentUserId = ctx.currentUserId
                    )

                    processor.processSwarmMessage(
                        threadAddress = result.message.senderOrSync.toAddress() as Address.Conversable,
                        message = result.message,
                        proto = result.proto,
                        context = ctx,
                        pro = result.pro,
                    )
                } catch (ec: Exception) {
                    Log.e(
                        logTag,
                        "Error while processing personal message with hash ${message.hash}",
                        ec
                    )
                }
            }
        }
    }

    private fun processConfig(messages: List<RetrieveMessageResponse.Message>, forConfig: UserConfigType) {
        if (messages.isEmpty()) {
            Log.d(logTag, "No messages to process for $forConfig")
            return
        }

        val newMessages = messages
            .asSequence()
            .filterNot { msg ->
                receivedMessageHashDatabase.checkOrUpdateDuplicateState(
                    swarmPublicKey = userPublicKey,
                    namespace = forConfig.namespace,
                    hash = msg.hash
                )
            }
            .map { m->
                ConfigMessage(
                    data = m.data,
                    hash = m.hash,
                    timestamp = m.timestamp.toEpochMilli()
                )
            }
            .toList()

        if (newMessages.isNotEmpty()) {
            try {
                configFactory.mergeUserConfigs(
                    userConfigType = forConfig,
                    messages = newMessages
                )
            } catch (e: Exception) {
                Log.e(logTag, "Error while merging user configs for $forConfig", e)
            }
        }

        Log.d(logTag, "Processed ${newMessages.size} new messages for config $forConfig")
    }


    private suspend fun poll(snode: Snode, pollOnlyUserProfileConfig: Boolean) = supervisorScope {
        val userAuth = requireNotNull(storage.userAuth)

        // Get messages call wrapped in an async
        val fetchMessageTask = if (!pollOnlyUserProfileConfig) {
            val retrieveMessageApi = retrieveMessageFactory.create(
                namespace = Namespace.DEFAULT(),
                lastHash = lokiApiDatabase.getLastMessageHashValue(
                    snode = snode,
                    publicKey = userAuth.accountId.hexString,
                    namespace = Namespace.DEFAULT()
                ),
                auth = userAuth,
                maxSize = -2
            )

            this.async {
                runCatching {
                    swarmApiExecutor.execute(
                        SwarmApiRequest(
                            swarmPubKeyHex = userAuth.accountId.hexString,
                            api = retrieveMessageApi,
                            swarmNodeOverride = snode,
                        )
                    )
                }
            }
        } else {
            null
        }

        // Determine which configs to fetch
        val configTypesToFetch = if (pollOnlyUserProfileConfig) listOf(UserConfigType.USER_PROFILE)
            else UserConfigType.entries.sortedBy { it.processingOrder }

        // Prepare a set to keep track of hashes of config messages we need to extend
        val hashesToExtend = mutableSetOf<String>()

        // Fetch the config messages in parallel, record the type and the result
        val configFetchTasks = configFactory.withUserConfigs { configs ->
            configTypesToFetch
                .map { type ->
                    val config = configs.getConfig(type)
                    hashesToExtend += config.activeHashes()
                    val retrieveApi = retrieveMessageFactory.create(
                        lastHash = lokiApiDatabase.getLastMessageHashValue(
                            snode = snode,
                            publicKey = userAuth.accountId.hexString,
                            namespace = type.namespace
                        ),
                        auth = userAuth,
                        namespace = type.namespace,
                        maxSize = -8
                    )

                    this.async {
                        type to runCatching {
                            swarmApiExecutor.execute(
                                SwarmApiRequest(
                                    swarmPubKeyHex = userAuth.accountId.hexString,
                                    api = retrieveApi,
                                    swarmNodeOverride = snode,
                                ),
                            )
                        }
                    }
                }
        }

        if (hashesToExtend.isNotEmpty()) {
            launch {
                try {
                    swarmApiExecutor.execute(
                        SwarmApiRequest(
                            swarmPubKeyHex = userAuth.accountId.hexString,
                            api = alterTtlApiFactory.create(
                                messageHashes = hashesToExtend,
                                auth = userAuth,
                                alterType = AlterTtlApi.AlterType.Extend,
                                newExpiry = snodeClock.currentTimeMillis() + 14.days.inWholeMilliseconds
                            ),
                            swarmNodeOverride = snode,
                        )
                    )
                } catch (e: Exception) {
                    Log.e(logTag, "Error while extending TTL for hashes", e)
                }
            }
        }

        // From here, we will await on the results of pending tasks

        // Always process the configs before the messages
        for (task in configFetchTasks) {
            val (configType, result) = task.await()

            val messages = result.getOrThrow().messages
            processConfig(messages = messages, forConfig = configType)

            if (messages.isNotEmpty()) {
                lokiApiDatabase.setLastMessageHashValue(
                    snode = snode,
                    publicKey = userPublicKey,
                    newValue = messages
                        .maxBy { it.timestamp }.hash,
                    namespace = configType.namespace
                )
            }
        }

        // Process the messages if we requested them
        if (fetchMessageTask != null) {
            val messages = fetchMessageTask.await().getOrThrow().messages
            processPersonalMessages(messages)

            messages.maxByOrNull { it.timestamp }?.let { newest ->
                lokiApiDatabase.setLastMessageHashValue(
                    snode = snode,
                    publicKey = userPublicKey,
                    newValue = newest.hash,
                    namespace = Namespace.DEFAULT()
                )
            }
        }
    }

    private val UserConfigType.processingOrder: Int
        get() = when (this) {
            UserConfigType.USER_PROFILE -> 0
            UserConfigType.CONTACTS -> 1
            UserConfigType.CONVO_INFO_VOLATILE -> 2
            UserConfigType.USER_GROUPS -> 3
        }
}

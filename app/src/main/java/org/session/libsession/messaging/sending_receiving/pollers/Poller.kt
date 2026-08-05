package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.messages.Message.Companion.senderOrSync
import org.session.libsession.messaging.sending_receiving.MessageParser
import org.session.libsession.messaging.sending_receiving.ReceivedMessageProcessor
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.AlterTtlApi
import org.thoughtcrime.securesms.api.snode.RetrieveMessageApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.api.snode.execute
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.api.swarm.SwarmSnodeSelector
import org.thoughtcrime.securesms.api.swarm.execute
import org.thoughtcrime.securesms.configs.ExpiredConfigRecovery
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

class Poller @Inject constructor(
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
    private val swarmDirectory: SwarmDirectory,
    private val snodeApiExecutor: SnodeApiExecutor,
    private val expiredConfigRecovery: ExpiredConfigRecovery,
    appVisibilityManager: AppVisibilityManager,
) : BasePoller<Unit>(
    debugLabel = "MainPoller",
    networkConnectivity = networkConnectivity,
    appVisibilityManager = appVisibilityManager
) {
    private val userPublicKey: String
        get() = storage.getUserPublicKey().orEmpty()

    companion object {
        private val hasMigratedToMultiPartConfigKey = PreferenceKey.boolean("migrated_to_multi_part_config")
        private val hadSuccessfulPollKey = PreferenceKey.boolean("poller.had_successful_poll")
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

        if (!prefs[hadSuccessfulPollKey]) {
            pollInitialUserProfile()
            prefs[hadSuccessfulPollKey] = true
        } else {
            poll(swarmSnodeSelector.selectSnode(userPublicKey))
        }
    }

    // region Private API
    private fun processPersonalMessages(messages: List<RetrieveMessageResponse.Message>) {
        if (messages.isEmpty()) {
            log("No personal messages to process")
            return
        }

        log("Received ${messages.size} personal messages from snode")

        processor.startProcessing("Poller") { ctx ->
            for (message in messages) {
                if (receivedMessageHashDatabase.checkOrUpdateDuplicateState(
                        swarmPublicKey = userPublicKey,
                        namespace = Namespace.DEFAULT(),
                        hash = message.hash
                    )) {
                    log("Skipping duplicated message ${message.hash}")
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
                    logE("Error while processing personal message with hash ${message.hash}", ec)
                }
            }
        }
    }

    /**
     * @return whether everything fetched was taken in. A merge failure is swallowed deliberately — one
     *  bad config message shouldn't fail the whole poll — but it does mean local state is *not* level
     *  with the swarm, which the caller needs to know before it permits an expired-config re-store.
     */
    private fun processConfig(
        messages: List<RetrieveMessageResponse.Message>,
        forConfig: UserConfigType,
    ): Boolean {
        if (messages.isEmpty()) {
            log("No messages to process for $forConfig")
            return true
        }

        // Note this marks each hash as SEEN, before the merge below has had a chance to fail — and the
        // last-hash cursor advances on a successful fetch too. So "seen" is not "incorporated", and a
        // message that fails to merge is never offered to us again by either mechanism. Anything that
        // needs to know local state took everything in must track that separately; it cannot infer it
        // from a later poll coming back clean, because it always will.
        val newMessages = messages
            .asSequence()
            .filterNot { msg ->
                receivedMessageHashDatabase.checkOrUpdateDuplicateState(
                    swarmPublicKey = userPublicKey,
                    namespace = forConfig.namespace,
                    hash = msg.hash
                )
            }
            .map { it.toConfigMessage() }
            .toList()

        var tookEverythingIn = true

        if (newMessages.isNotEmpty()) {
            try {
                val merged = configFactory.mergeUserConfigs(
                    userConfigType = forConfig,
                    messages = newMessages
                )

                // Merging is tolerant of a message that won't parse or verify: it skips it, takes the
                // rest, and returns normally. So a clean return is not evidence everything landed —
                // compare the count. 2-of-3 merging is indistinguishable from 3-of-3 otherwise.
                if (merged < newMessages.size) {
                    logE("Only merged $merged of ${newMessages.size} messages for config $forConfig")
                    tookEverythingIn = false
                }
            } catch (e: Exception) {
                logE("Error while merging user configs for $forConfig", e)
                tookEverythingIn = false
            }
        }

        log("Processed ${newMessages.size} new messages for config $forConfig")
        return tookEverythingIn
    }

    private fun RetrieveMessageResponse.Message.toConfigMessage(): ConfigMessage {
        return ConfigMessage(
            hash = this.hash,
            data = this.data,
            timestamp = this.timestamp.toEpochMilli()
        )
    }

    private suspend fun poll(snode: Snode) = supervisorScope {
        val userAuth = requireNotNull(storage.userAuth)

        // Get messages call wrapped in an async
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

        val fetchMessageTask = this.async {
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

        // Prepare a set to keep track of hashes of config messages we need to extend
        val hashesToExtend = mutableSetOf<String>()

        // Fetch the config messages in parallel, record the type and the result
        val configFetchTasks = configFactory.withUserConfigs { configs ->
            UserConfigType.entries.sortedBy { it.processingOrder }
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

        // The extension response doubles as our only signal that a config has been swept from the
        // swarm, so keep hold of it. It's awaited after the merge below, because putting a config
        // back before merging what we just fetched is how a long-offline device overwrites newer
        // state with older.
        val extendTask = hashesToExtend.takeIf { it.isNotEmpty() }?.let { hashes ->
            async {
                runCatching {
                    swarmApiExecutor.execute(
                        SwarmApiRequest(
                            swarmPubKeyHex = userAuth.accountId.hexString,
                            api = alterTtlApiFactory.create(
                                messageHashes = hashes,
                                auth = userAuth,
                                alterType = AlterTtlApi.AlterType.Extend,
                                newExpiry = snodeClock.currentTimeMillis() + SnodeMessage.CONFIG_TTL
                            ),
                            swarmNodeOverride = snode,
                        )
                    )
                }.onFailure { e ->
                    if (e is CancellationException) throw e

                    logE("Error while extending TTL for hashes", e)
                }
            }
        }

        // Everything above is launched before anything is awaited, and that ordering is load-bearing:
        // requests sharing a snode coalesce into one batch inside a 100ms window (BatchApiExecutor), so a
        // group of retrieves plus the extend go out as roughly one round-trip. Nothing enforces it —
        // inserting an await longer than the window between those launches silently splits the batch and
        // no test or error would show it, and the window itself is only documented two layers down.

        // From here, we will await on the results of pending tasks

        var mergedAnyConfig = false
        var tookEverythingIn = true

        // Always process the configs before the messages
        for (task in configFetchTasks) {
            val (configType, result) = task.await()

            val messages = result.getOrThrow().messages
            if (!processConfig(messages = messages, forConfig = configType)) {
                tookEverythingIn = false
            }

            if (messages.isNotEmpty()) {
                mergedAnyConfig = true

                lokiApiDatabase.setLastMessageHashValue(
                    snode = snode,
                    publicKey = userPublicKey,
                    newValue = messages
                        .maxBy { it.timestamp }.hash,
                    namespace = configType.namespace
                )
            }
        }

        // Process the messages
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

        // Left until last: the configs above have been taken in, which is what makes it safe to put
        // back anything the swarm has lost, and nothing else should wait on the expire response.
        //
        // Reached whether or not there was anything to merge, and it must stay that way — a device whose
        // configs have expired gets nothing back, so gating this on `mergedAnyConfig` would make recovery
        // unreachable for exactly the devices that need it.
        //
        // It is *not* reached when any config namespace failed to fetch, because the per-namespace
        // `getOrThrow()` above throws out of the poll first. That's what keeps "the swarm has nothing"
        // apart from both "nothing answered" and "some namespaces answered and some didn't" — a partial
        // answer tells us nothing about the namespaces that stayed silent, so it must not count as
        // level. If you ever restructure this loop to collect failures instead of throwing, that
        // property has to be preserved deliberately: "at least one namespace answered" is not enough.
        //
        // A failed *merge* is the third case, and it's the one that hides: processConfig swallows those
        // so one bad message can't fail the whole poll, which means a successful fetch is not by itself
        // proof we took anything in. If we didn't, the swarm still holds config we haven't incorporated
        // and we are not level with it — so say so rather than authorising a re-store.
        if (tookEverythingIn) {
            expiredConfigRecovery.markLocalStateLevelWithSwarm(
                swarmPubKeyHex = userAuth.accountId.hexString,
                mergedConfigMessagesForDiagnosticsOnly = mergedAnyConfig,
            )
        } else {
            expiredConfigRecovery.markMergeIncompleteForSwarm(userAuth.accountId.hexString)
        }

        extendTask?.await()?.getOrNull()?.let { result ->
            expiredConfigRecovery.onUserConfigsChecked(auth = userAuth, report = result.expiry)
        }
    }

    private suspend fun pollInitialUserProfile() = supervisorScope {
        val auth = requireNotNull(storage.userAuth) {
            "User auth is required for initial profile polling"
        }

        val swarm = swarmDirectory.getSwarm(auth.accountId.hexString)
        require(swarm.isNotEmpty()) {
            "Swarm is empty for user ${auth.accountId.hexString}"
        }

        log("Start initial user profile polling from ${swarm.size} snodes")

        val fetchMessageTasks = swarm.map { snode ->
            snode to async {
                // Must not take too long
                requireNotNull(withTimeoutOrNull(10.seconds) {
                    snodeApiExecutor.execute(
                        SnodeApiRequest(
                            snode = snode,
                            api = retrieveMessageFactory.create(
                                namespace = Namespace.USER_PROFILE(),
                                auth = auth,
                                lastHash = null,
                                maxSize = null,
                            )
                        )
                    )
                }) {
                    "Timeout waiting for result from $snode"
                }
            }
        }

        val results = fetchMessageTasks.map { (snode, deferred) ->
            runCatching {
                deferred.await()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                log("Error polling initial config from $snode")
            }
        }

        if (results.all { it.isFailure }) {
            throw results.fold(null as Throwable?) { acc, result ->
                if (acc == null) {
                    result.exceptionOrNull()!!
                } else {
                    acc.addSuppressed(result.exceptionOrNull()!!)
                    acc
                }
            }!!
        } else {
            val messages = results
                .asSequence()
                .flatMap { result ->
                    when {
                        result.isSuccess -> {
                            result.getOrThrow().messages.asSequence()
                        }

                        else -> {
                            logE("Failed to fetch initial profile config from one snode", result.exceptionOrNull())
                            emptySequence()
                        }
                    }
                }
                .map { it.toConfigMessage() }
                .toList()

            configFactory.mergeUserConfigs(
                userConfigType = UserConfigType.USER_PROFILE,
                messages = messages
            )

            log("Merged ${messages.size} config messages for initial profile poll")
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

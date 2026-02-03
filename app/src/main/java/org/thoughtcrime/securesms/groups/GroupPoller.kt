package org.thoughtcrime.securesms.groups

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.messaging.sending_receiving.MessageParser
import org.session.libsession.messaging.sending_receiving.ReceivedMessageProcessor
import org.session.libsession.messaging.sending_receiving.pollers.BasePoller
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.AlterTtlApi
import org.thoughtcrime.securesms.api.snode.RetrieveMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.api.swarm.SwarmSnodeSelector
import org.thoughtcrime.securesms.api.swarm.execute
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days

class GroupPoller @AssistedInject constructor(
    @Assisted scope: CoroutineScope,
    @Assisted private val groupId: AccountId,
    @Assisted private val pollSemaphore: Semaphore,
    private val configFactoryProtocol: ConfigFactoryProtocol,
    private val lokiApiDatabase: LokiAPIDatabaseProtocol,
    private val clock: SnodeClock,
    private val groupRevokedMessageHandler: GroupRevokedMessageHandler,
    private val receivedMessageHashDatabase: ReceivedMessageHashDatabase,
    private val messageParser: MessageParser,
    private val receivedMessageProcessor: ReceivedMessageProcessor,
    private val retrieveMessageFactory: RetrieveMessageApi.Factory,
    private val alterTtlApiApiFactory: AlterTtlApi.Factory,
    private val swarmApiExecutor: SwarmApiExecutor,
    private val swarmSnodeSelector: SwarmSnodeSelector,
    networkConnectivity: NetworkConnectivity,
): BasePoller<GroupPoller.GroupPollResult>(networkConnectivity, scope) {
    data class GroupPollResult(
        val groupExpired: Boolean?
    )

    override suspend fun doPollOnce(isFirstPollSinceApoStarted: Boolean): GroupPollResult = pollSemaphore.withPermit {
        var groupExpired: Boolean? = null

        val result = runCatching {
            supervisorScope {
                val snode = swarmSnodeSelector.selectSnode(groupId.hexString)

                val groupAuth =
                    configFactoryProtocol.getGroupAuth(groupId) ?: return@supervisorScope
                val configHashesToExtends = configFactoryProtocol.withGroupConfigs(groupId) {
                    buildSet {
                        addAll(it.groupKeys.activeHashes())
                        addAll(it.groupInfo.activeHashes())
                        addAll(it.groupMembers.activeHashes())
                    }
                }

                val group = configFactoryProtocol.getGroup(groupId)
                if (group == null) {
                    throw NonRetryableException("Group doesn't exist")
                }

                if (group.kicked) {
                    throw NonRetryableException("Group has been kicked")
                }

                Log.v(logTag, "Start polling group($groupId) message snode = ${snode.ip}")

                val adminKey = group.adminKey

                val pollingTasks = mutableListOf<Pair<String, Deferred<*>>>()

                val receiveRevokeMessage = async {
                    swarmApiExecutor.execute(
                        SwarmApiRequest(
                            swarmNodeOverride = snode,
                            swarmPubKeyHex = groupId.hexString,
                            api = retrieveMessageFactory.create(
                                lastHash = lokiApiDatabase.getLastMessageHashValue(
                                    snode,
                                    groupId.hexString,
                                    Namespace.REVOKED_GROUP_MESSAGES()
                                ).orEmpty(),
                                auth = groupAuth,
                                namespace = Namespace.REVOKED_GROUP_MESSAGES(),
                                maxSize = null,
                            )
                        )
                    ).messages
                }

                if (configHashesToExtends.isNotEmpty() && adminKey != null) {
                    pollingTasks += "extending group config TTL" to async {
                        swarmApiExecutor.execute(
                            SwarmApiRequest(
                                swarmNodeOverride = snode,
                                swarmPubKeyHex = groupId.hexString,
                                api = alterTtlApiApiFactory.create(
                                    messageHashes = configHashesToExtends,
                                    auth = groupAuth,
                                    alterType = AlterTtlApi.AlterType.Extend,
                                    newExpiry = clock.currentTimeMillis() + 14.days.inWholeMilliseconds,
                                )
                            )
                        )
                    }
                }

                val groupMessageRetrieval = async {
                    val lastHash = lokiApiDatabase.getLastMessageHashValue(
                        snode,
                        groupId.hexString,
                        Namespace.GROUP_MESSAGES()
                    ).orEmpty()


                    swarmApiExecutor.execute(
                        SwarmApiRequest(
                            swarmNodeOverride = snode,
                            swarmPubKeyHex = groupId.hexString,
                            api = retrieveMessageFactory.create(
                                lastHash = lastHash,
                                auth = groupAuth,
                                namespace = Namespace.GROUP_MESSAGES(),
                                maxSize = null,
                            )
                        )
                    )
                }

                val groupConfigRetrieval = listOf(
                    Namespace.GROUP_KEYS(),
                    Namespace.GROUP_INFO(),
                    Namespace.GROUP_MEMBERS()
                ).map { ns ->
                    async {
                        swarmApiExecutor.execute(
                            SwarmApiRequest(
                                swarmPubKeyHex = groupId.hexString,
                                swarmNodeOverride = snode,
                                api = retrieveMessageFactory.create(
                                    lastHash = lokiApiDatabase.getLastMessageHashValue(
                                        snode,
                                        groupId.hexString,
                                        ns
                                    ).orEmpty(),
                                    auth = groupAuth,
                                    namespace = ns,
                                    maxSize = null,
                                )
                            )
                        ).messages
                    }
                }

                // The retrieval of the all group messages can be done concurrently,
                // however, in order for the messages to be able to be decrypted, the config messages
                // must be processed first.
                pollingTasks += "polling and handling group config keys and messages" to async {
                    val result = runCatching {
                        val (keysMessage, infoMessage, membersMessage) = groupConfigRetrieval.awaitAll()
                        handleGroupConfigMessages(keysMessage, infoMessage, membersMessage)
                        saveLastMessageHash(snode, keysMessage, Namespace.GROUP_KEYS())
                        saveLastMessageHash(snode, infoMessage, Namespace.GROUP_INFO())
                        saveLastMessageHash(snode, membersMessage, Namespace.GROUP_MEMBERS())

                        groupExpired = configFactoryProtocol.withGroupConfigs(groupId) {
                            it.groupKeys.size() == 0
                        }

                        val regularMessages = groupMessageRetrieval.await()
                        handleMessages(regularMessages.messages)

                        regularMessages.messages.maxByOrNull { it.timestamp }?.let { newest ->
                            lokiApiDatabase.setLastMessageHashValue(
                                snode = snode,
                                publicKey = groupId.hexString,
                                newValue = newest.hash,
                                namespace = Namespace.GROUP_MESSAGES()
                            )
                        }
                    }

                    // Revoke message must be handled regardless, and at the end
                    val revokedMessages = receiveRevokeMessage.await()
                    handleRevoked(revokedMessages)
                    saveLastMessageHash(snode, revokedMessages, Namespace.REVOKED_GROUP_MESSAGES())

                    // Propagate any prior exceptions
                    result.getOrThrow()
                }

                // Wait for all tasks to complete, gather any exceptions happened during polling
                val errors = pollingTasks.mapNotNull { (name, task) ->
                    runCatching { task.await() }
                        .exceptionOrNull()
                        ?.takeIf { it !is CancellationException }
                        ?.let { RuntimeException("Error $name", it) }
                }

                // If there were any errors, throw the first one and add the rest as "suppressed" exceptions
                if (errors.isNotEmpty()) {
                    throw errors.first().apply {
                        for (index in 1 until errors.size) {
                            addSuppressed(errors[index])
                        }
                    }
                }
            }
        }

        Log.d(logTag, "Group($groupId) polling completed, success = ${result.isSuccess}")

        result.getOrThrow()

        GroupPollResult(
            groupExpired = groupExpired
        )
    }

    private fun RetrieveMessageResponse.Message.toConfigMessage(): ConfigMessage {
        return ConfigMessage(hash, data, timestamp.toEpochMilli())
    }

    private fun saveLastMessageHash(
        snode: Snode,
        messages: List<RetrieveMessageResponse.Message>,
        namespace: Int
    ) {
        if (messages.isNotEmpty()) {
            lokiApiDatabase.setLastMessageHashValue(
                snode = snode,
                publicKey = groupId.hexString,
                newValue = messages.last().hash,
                namespace = namespace
            )
        }
    }

    private suspend fun handleRevoked(messages: List<RetrieveMessageResponse.Message>) {
        groupRevokedMessageHandler.handleRevokeMessage(groupId, messages.map { it.data })
    }

    private fun handleGroupConfigMessages(
        keysResponse: List<RetrieveMessageResponse.Message>,
        infoResponse: List<RetrieveMessageResponse.Message>,
        membersResponse: List<RetrieveMessageResponse.Message>
    ) {
        if (keysResponse.isEmpty() && infoResponse.isEmpty() && membersResponse.isEmpty()) {
            return
        }

        Log.d(
            logTag, "Handling group config messages(" +
                    "info = ${infoResponse.size}, " +
                    "keys = ${keysResponse.size}, " +
                    "members = ${membersResponse.size})"
        )

        configFactoryProtocol.mergeGroupConfigMessages(
            groupId = groupId,
            keys = keysResponse.map { it.toConfigMessage() },
            info = infoResponse.map { it.toConfigMessage() },
            members = membersResponse.map { it.toConfigMessage() },
        )
    }

    private fun handleMessages(messages: List<RetrieveMessageResponse.Message>) {
        if (messages.isEmpty()) {
            return
        }

        val start = System.currentTimeMillis()
        val threadAddress = Address.Group(groupId)

        receivedMessageProcessor.startProcessing("GroupPoller($groupId)") { ctx ->
            for (message in messages) {
                if (receivedMessageHashDatabase.checkOrUpdateDuplicateState(
                        swarmPublicKey = groupId.hexString,
                        namespace = Namespace.GROUP_MESSAGES(),
                        hash = message.hash
                    )) {
                    Log.v(logTag, "Skipping duplicated group message ${message.hash} for group $groupId")
                    continue
                }

                try {
                    val result = messageParser.parseGroupMessage(
                        data = message.data,
                        serverHash = message.hash,
                        groupId = groupId,
                        currentUserId = ctx.currentUserId,
                        currentUserEd25519PrivKey = ctx.currentUserEd25519KeyPair.secretKey.data,
                    )

                    receivedMessageProcessor.processSwarmMessage(
                        threadAddress = threadAddress,
                        message = result.message,
                        proto = result.proto,
                        context = ctx,
                        pro = result.pro,
                    )
                } catch (e: Exception) {
                    Log.e(logTag, "Error handling group message", e)
                }
            }
        }

        Log.d(logTag, "Handled ${messages.size} group messages for $groupId in ${System.currentTimeMillis() - start}ms")
    }

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope, groupId: AccountId, pollSemaphore: Semaphore): GroupPoller
    }
}

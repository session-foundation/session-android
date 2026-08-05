package org.thoughtcrime.securesms.groups

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
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
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.truncatedForDisplay
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.AlterTtlApi
import org.thoughtcrime.securesms.api.snode.RetrieveMessageApi
import org.thoughtcrime.securesms.api.snode.groupExpiredFromExpiryCheck
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.api.swarm.SwarmSnodeSelector
import org.thoughtcrime.securesms.api.swarm.execute
import org.thoughtcrime.securesms.configs.ExpiredConfigRecovery
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import kotlin.coroutines.cancellation.CancellationException

class GroupPoller @AssistedInject constructor(
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
    private val expiredConfigRecovery: ExpiredConfigRecovery,
    networkConnectivity: NetworkConnectivity,
    appVisibilityManager: AppVisibilityManager,
): BasePoller<GroupPoller.GroupPollResult>(
    networkConnectivity = networkConnectivity,
    appVisibilityManager = appVisibilityManager,
    debugLabel = "GroupPoller(${groupId.truncatedForDisplay()})"
) {
    data class GroupPollResult(
        val groupExpired: Boolean?
    )

    /**
     * The active hashes of a group's three configs, kept attributed.
     *
     * Only [all] is sent on the wire; the individual sets exist so the response can be read back
     * per-config, which is what lets the keys config alone decide whether the group is expired.
     */
    private data class GroupConfigHashes(
        val keys: Set<String>,
        val info: Set<String>,
        val members: Set<String>,
    ) {
        val all: Set<String> get() = keys + info + members
    }

    override suspend fun doPollOnce(isFirstPollSinceAppStarted: Boolean): GroupPollResult = pollSemaphore.withPermit {
        var groupExpired: Boolean? = null

        val result = runCatching {
            supervisorScope {
                val snode = swarmSnodeSelector.selectSnode(groupId.hexString)

                val groupAuth =
                    configFactoryProtocol.getGroupAuth(groupId) ?: return@supervisorScope
                // Keep the three sets apart rather than merging them here: the expire response is read
                // back to find out which configs the swarm has lost, and only the *keys* hashes decide
                // whether the group is expired. A flat union can't be attributed, so merge only where
                // the request payload is built.
                val configHashes = configFactoryProtocol.withGroupConfigs(groupId) {
                    GroupConfigHashes(
                        keys = it.groupKeys.activeHashes().toSet(),
                        info = it.groupInfo.activeHashes().toSet(),
                        members = it.groupMembers.activeHashes().toSet(),
                    )
                }

                val group = configFactoryProtocol.getGroup(groupId)
                if (group == null) {
                    throw NonRetryableException("Group doesn't exist")
                }

                if (group.kicked) {
                    throw NonRetryableException("Group has been kicked")
                }

                log("Start polling group($groupId) message snode = ${snode.ip}")

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

                // Any member can extend, not just an admin: the request authenticates with
                // `groupAuth`, and the storage server explicitly supports a member doing this. Gating
                // it on an admin key meant a group whose admins went quiet lost its configs at 30
                // days while active members polled it daily.
                //
                // The response also doubles as our only signal that a config has been swept from the
                // swarm, so keep hold of it. It's read after the merge below, because putting a
                // config back before merging what we just fetched is how a long-offline device
                // overwrites newer state with older.
                val extendTask: Deferred<AlterTtlApi.Result>? =
                    if (configHashes.all.isNotEmpty()) {
                        async {
                            swarmApiExecutor.execute(
                                SwarmApiRequest(
                                    swarmNodeOverride = snode,
                                    swarmPubKeyHex = groupId.hexString,
                                    api = alterTtlApiApiFactory.create(
                                        messageHashes = configHashes.all,
                                        auth = groupAuth,
                                        alterType = AlterTtlApi.AlterType.Extend,
                                        newExpiry = clock.currentTimeMillis() + SnodeMessage.CONFIG_TTL,
                                    )
                                )
                            )
                        }.also { pollingTasks += "extending group config TTL" to it }
                    } else {
                        null
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
                        val tookEverythingIn =
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

                        // Left until last: the configs above have been taken in, which is what makes it
                        // safe to put back anything the swarm has lost, and nothing else should wait
                        // on the expire response. Its failure is already reported via pollingTasks.
                        val expiryReport = extendTask?.let { task ->
                            runCatching { task.await() }.getOrNull()?.expiry
                        }

                        // Reached whether or not there was anything to merge, and it must stay that
                        // way — a group whose configs have expired returns nothing, so gating this on
                        // having merged something would make recovery unreachable for exactly the
                        // groups that need it.
                        //
                        // It is *not* reached when any of the three namespaces failed: awaitAll()
                        // rethrows the first failure, so a partial answer never counts as level. Nor
                        // when the merge threw, since handleGroupConfigMessages lets that propagate to
                        // the outer runCatching.
                        //
                        // `tookEverythingIn` covers the case neither of those catches: a merge that
                        // skips a message it can't parse and returns normally. No error, nothing to
                        // catch, and the swarm still holds config we haven't incorporated.
                        if (tookEverythingIn) {
                            expiredConfigRecovery.markLocalStateLevelWithSwarm(
                                swarmPubKeyHex = groupId.hexString,
                                mergedConfigMessagesForDiagnosticsOnly = keysMessage.isNotEmpty() ||
                                        infoMessage.isNotEmpty() ||
                                        membersMessage.isNotEmpty(),
                            )
                        } else {
                            expiredConfigRecovery.markMergeIncompleteForSwarm(groupId.hexString)
                        }

                        // The keys hashes alone decide this, and only when the check actually had an
                        // answer — otherwise the empty-keys check above stands.
                        groupExpiredFromExpiryCheck(expiryReport, configHashes.keys)?.let {
                            groupExpired = it
                        }

                        if (expiryReport != null) {
                            expiredConfigRecovery.onGroupConfigsChecked(
                                groupId = groupId,
                                auth = groupAuth,
                                report = expiryReport,
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

        log("Group($groupId) polling completed, success = ${result.isSuccess}")

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

    /**
     * @return whether every message handed to the merge was actually taken in. Merging skips a message
     *  it can't parse or verify and returns normally, so a clean return is not evidence of that — the
     *  count has to be compared. Callers whose correctness depends on being level with the swarm need
     *  this; callers that just want the configs applied can ignore it.
     */
    private fun handleGroupConfigMessages(
        keysResponse: List<RetrieveMessageResponse.Message>,
        infoResponse: List<RetrieveMessageResponse.Message>,
        membersResponse: List<RetrieveMessageResponse.Message>
    ): Boolean {
        if (keysResponse.isEmpty() && infoResponse.isEmpty() && membersResponse.isEmpty()) {
            return true
        }

        log("Handling group config messages(" +
                    "info = ${infoResponse.size}, " +
                    "keys = ${keysResponse.size}, " +
                    "members = ${membersResponse.size})"
        )

        val given = keysResponse.size + infoResponse.size + membersResponse.size
        val merged = configFactoryProtocol.mergeGroupConfigMessages(
            groupId = groupId,
            keys = keysResponse.map { it.toConfigMessage() },
            info = infoResponse.map { it.toConfigMessage() },
            members = membersResponse.map { it.toConfigMessage() },
        )

        if (merged < given) {
            logE("Only merged $merged of $given group config messages")
        }

        return merged == given
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
                    log("Skipping duplicated group message ${message.hash}")
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
                    logE("Error handling group message", e)
                }
            }
        }

        log("Handled ${messages.size} group messages in ${System.currentTimeMillis() - start}ms")
    }

    @AssistedFactory
    interface Factory {
        fun create(groupId: AccountId, pollSemaphore: Semaphore): GroupPoller
    }
}

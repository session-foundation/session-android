package org.thoughtcrime.securesms.groups

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import network.loki.messenger.libsession_util.util.Sodium
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.snode.RawResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.getGroup
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.util.AppVisibilityManager
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days

class GroupPoller(
    scope: CoroutineScope,
    private val groupId: AccountId,
    private val configFactoryProtocol: ConfigFactoryProtocol,
    private val groupManagerV2: GroupManagerV2,
    private val storage: StorageProtocol,
    private val lokiApiDatabase: LokiAPIDatabaseProtocol,
    private val clock: SnodeClock,
    private val appVisibilityManager: AppVisibilityManager,
) {
    companion object {
        private const val POLL_INTERVAL = 3_000L

        private const val TAG = "GroupPoller"
    }

    data class State(
        val hadAtLeastOneSuccessfulPoll: Boolean = false,
        val lastPoll: PollResult? = null,
        val inProgress: Boolean = false,
    )

    data class PollResult(
        val startedAt: Instant,
        val finishedAt: Instant,
        val result: Result<Unit>,
        val groupExpired: Boolean?
    ) {
        fun hasNonRetryableError(): Boolean {
            val e = result.exceptionOrNull()
            return e != null && (e is NonRetryableException || e is CancellationException)
        }
    }

    private class InternalPollState(
        var swarmNodes: MutableSet<Snode> = mutableSetOf(),
        var currentSnode: Snode? = null,
    )

    // A channel to send tokens to trigger a poll
    private val pollOnceTokens = Channel<PollOnceToken>()

    // A flow that represents the state of the poller.
    val state: StateFlow<State> = flow {
        var lastState = State()
        val pendingTokens = mutableListOf<PollOnceToken>()
        val internalPollState = InternalPollState()

        while (true) {
            pendingTokens.add(pollOnceTokens.receive())

            // Drain all the tokens we've received up to this point, so we can reply them all at once
            while (true) {
                val result = pollOnceTokens.tryReceive()
                result.getOrNull()?.let(pendingTokens::add) ?: break
            }

            lastState = lastState.copy(inProgress = true).also { emit(it) }

            val pollResult = doPollOnce(internalPollState)

            lastState = lastState.copy(
                hadAtLeastOneSuccessfulPoll = lastState.hadAtLeastOneSuccessfulPoll || pollResult.result.isSuccess,
                lastPoll = pollResult,
                inProgress = false
            ).also { emit(it) }

            // Notify all pending tokens
            pendingTokens.forEach { it.resultCallback.send(pollResult) }
            pendingTokens.clear()
        }
    }.stateIn(scope, SharingStarted.Eagerly, State())

    init {
        // This coroutine is here to periodically request polling the group when the app
        // becomes visible
        scope.launch {
            while (true) {
                // Wait for the app becomes visible
                appVisibilityManager.isAppVisible.first { visible -> visible }

                // As soon as the app becomes visible, start polling
                if (requestPollOnce().hasNonRetryableError()) {
                    Log.v(TAG, "Error polling group $groupId and stopped polling")
                    break
                }

                // As long as the app is visible, keep polling
                while (true) {
                    // Wait POLL_INTERVAL
                    delay(POLL_INTERVAL)

                    val appInBackground = !appVisibilityManager.isAppVisible.value

                    if (appInBackground) {
                        Log.d(TAG, "App became invisible, stopping polling group $groupId")
                        break
                    }

                    if (requestPollOnce().hasNonRetryableError()) {
                        Log.v(TAG, "Error polling group $groupId and stopped polling")
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Request to poll the group once and return the result. It's guaranteed that
     * the poll will be run AT LEAST once after the request is sent, but it's not guaranteed
     * that one request will result in one poll, as the poller may choose to batch multiple requests
     * together.
     */
    suspend fun requestPollOnce(): PollResult {
        val resultChannel = Channel<PollResult>()
        pollOnceTokens.send(PollOnceToken(resultChannel))
        return resultChannel.receive()
    }

    private suspend fun doPollOnce(pollState: InternalPollState): PollResult {
        val pollStartedAt = Instant.now()
        var groupExpired: Boolean? = null

        val result = runCatching {
            supervisorScope {
                // Grab current snode or pick (and remove) a random one from the pool
                val snode = pollState.currentSnode ?: run {
                    if (pollState.swarmNodes.isEmpty()) {
                        Log.d(TAG, "Fetching swarm nodes for $groupId")
                        pollState.swarmNodes.addAll(SnodeAPI.fetchSwarmNodes(groupId.hexString))
                    }

                    check(pollState.swarmNodes.isNotEmpty()) { "No swarm nodes found" }
                    pollState.swarmNodes.random().also {
                        pollState.currentSnode = it
                        pollState.swarmNodes.remove(it)
                    }
                }

                val groupAuth =
                    configFactoryProtocol.getGroupAuth(groupId) ?: return@supervisorScope
                val configHashesToExtends = configFactoryProtocol.withGroupConfigs(groupId) {
                    buildSet {
                        addAll(it.groupKeys.currentHashes())
                        addAll(it.groupInfo.currentHashes())
                        addAll(it.groupMembers.currentHashes())
                    }
                }

                val group = configFactoryProtocol.getGroup(groupId)
                if (group == null) {
                    throw NonRetryableException("Group doesn't exist")
                }

                if (group.kicked) {
                    throw NonRetryableException("Group has been kicked")
                }

                val adminKey = group.adminKey

                val pollingTasks = mutableListOf<Pair<String, Deferred<*>>>()

                val receiveRevokeMessage = async {
                    SnodeAPI.sendBatchRequest(
                        snode,
                        groupId.hexString,
                        SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                            lastHash = lokiApiDatabase.getLastMessageHashValue(
                                snode,
                                groupId.hexString,
                                Namespace.REVOKED_GROUP_MESSAGES()
                            ).orEmpty(),
                            auth = groupAuth,
                            namespace = Namespace.REVOKED_GROUP_MESSAGES(),
                            maxSize = null,
                        ),
                        RetrieveMessageResponse::class.java
                    ).messages.filterNotNull()
                }

                if (configHashesToExtends.isNotEmpty() && adminKey != null) {
                    pollingTasks += "extending group config TTL" to async {
                        SnodeAPI.sendBatchRequest(
                            snode,
                            groupId.hexString,
                            SnodeAPI.buildAuthenticatedAlterTtlBatchRequest(
                                messageHashes = configHashesToExtends.toList(),
                                auth = groupAuth,
                                newExpiry = clock.currentTimeMills() + 14.days.inWholeMilliseconds,
                                extend = true
                            ),
                        )
                    }
                }

                val groupMessageRetrieval = async {
                    val lastHash = lokiApiDatabase.getLastMessageHashValue(
                        snode,
                        groupId.hexString,
                        Namespace.CLOSED_GROUP_MESSAGES()
                    ).orEmpty()

                    Log.d(TAG, "Retrieving group message since lastHash = $lastHash")

                    SnodeAPI.sendBatchRequest(
                        snode = snode,
                        publicKey = groupId.hexString,
                        request = SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                            lastHash = lastHash,
                            auth = groupAuth,
                            namespace = Namespace.CLOSED_GROUP_MESSAGES(),
                            maxSize = null,
                        ),
                        responseType = Map::class.java
                    )
                }

                val groupConfigRetrieval = listOf(
                    Namespace.ENCRYPTION_KEYS(),
                    Namespace.CLOSED_GROUP_INFO(),
                    Namespace.CLOSED_GROUP_MEMBERS()
                ).map { ns ->
                    async {
                        SnodeAPI.sendBatchRequest(
                            snode = snode,
                            publicKey = groupId.hexString,
                            request = SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                                lastHash = lokiApiDatabase.getLastMessageHashValue(
                                    snode,
                                    groupId.hexString,
                                    ns
                                ).orEmpty(),
                                auth = groupAuth,
                                namespace = ns,
                                maxSize = null,
                            ),
                            responseType = RetrieveMessageResponse::class.java
                        ).messages.filterNotNull()
                    }
                }

                // The retrieval of the all group messages can be done concurrently,
                // however, in order for the messages to be able to be decrypted, the config messages
                // must be processed first.
                pollingTasks += "polling and handling group config keys and messages" to async {
                    val result = runCatching {
                        val (keysMessage, infoMessage, membersMessage) = groupConfigRetrieval.map { it.await() }
                        handleGroupConfigMessages(keysMessage, infoMessage, membersMessage)
                        saveLastMessageHash(snode, keysMessage, Namespace.ENCRYPTION_KEYS())
                        saveLastMessageHash(snode, infoMessage, Namespace.CLOSED_GROUP_INFO())
                        saveLastMessageHash(snode, membersMessage, Namespace.CLOSED_GROUP_MEMBERS())

                        groupExpired = configFactoryProtocol.withGroupConfigs(groupId) {
                            it.groupKeys.size() == 0
                        }

                        val regularMessages = groupMessageRetrieval.await()
                        handleMessages(regularMessages, snode)
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

        if (result.isFailure) {
            val error = result.exceptionOrNull()
            Log.e(TAG, "Error polling group", error)

            if (error !is NonRetryableException && error !is CancellationException) {
                // If the error can be retried, reset the current snode so we use another one
                pollState.currentSnode = null
            }
        }

        val pollResult = PollResult(
            startedAt = pollStartedAt,
            finishedAt = Instant.now(),
            result = result,
            groupExpired = groupExpired
        )

        Log.d(TAG, "Polling group $groupId result = $pollResult")

        return pollResult
    }

    private fun RetrieveMessageResponse.Message.toConfigMessage(): ConfigMessage {
        return ConfigMessage(hash, data, timestamp ?: clock.currentTimeMills())
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
        messages.forEach { msg ->
            val decoded = configFactoryProtocol.decryptForUser(
                msg.data,
                Sodium.KICKED_DOMAIN,
                groupId,
            )

            if (decoded != null) {
                // The message should be in the format of "<sessionIdPubKeyBinary><messageGenerationASCII>",
                // where the pub key is 32 bytes, so we need to have at least 33 bytes of data
                if (decoded.size < 33) {
                    Log.w(TAG, "Received an invalid kicked message, expecting at least 33 bytes, got ${decoded.size}")
                    return@forEach
                }

                val sessionId = AccountId(IdPrefix.STANDARD, decoded.copyOfRange(0, 32))
                val messageGeneration = decoded.copyOfRange(32, decoded.size).decodeToString().toIntOrNull()
                if (messageGeneration == null) {
                    Log.w(TAG, "Received an invalid kicked message: missing message generation")
                    return@forEach
                }

                val currentKeysGeneration = configFactoryProtocol.withGroupConfigs(groupId) {
                    it.groupKeys.currentGeneration()
                }

                val isForMe = sessionId.hexString == storage.getUserPublicKey()
                Log.d(TAG, "Received kicked message, for us? ${isForMe}, message key generation = $messageGeneration, our key generation = $currentKeysGeneration")

                if (isForMe && messageGeneration >= currentKeysGeneration) {
                    groupManagerV2.handleKicked(groupId)
                }
            }
        }
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
            TAG, "Handling group config messages(" +
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

    private fun handleMessages(body: RawResponse, snode: Snode) {
        val messages = configFactoryProtocol.withGroupConfigs(groupId) {
            SnodeAPI.parseRawMessagesResponse(
                rawResponse = body,
                snode = snode,
                publicKey = groupId.hexString,
                decrypt = it.groupKeys::decrypt,
                namespace = Namespace.CLOSED_GROUP_MESSAGES(),
            )
        }

        val parameters = messages.map { (envelope, serverHash) ->
            MessageReceiveParameters(
                envelope.toByteArray(),
                serverHash = serverHash,
                closedGroup = Destination.ClosedGroup(groupId.hexString)
            )
        }

        parameters.chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER).forEach { chunk ->
            val job = BatchMessageReceiveJob(chunk)
            JobQueue.shared.add(job)
        }

        if (messages.isNotEmpty()) {
            Log.d(TAG, "Received and handled ${messages.size} group messages")
        }
    }

    /**
     * A token to poll a group once and receive the result. Note that it's not guaranteed that
     * one token will trigger one poll, as the poller may batch multiple requests together.
     */
    private data class PollOnceToken(val resultCallback: SendChannel<PollResult>)
}

package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.util.Sodium
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.snode.RawResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.getClosedGroup
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import org.session.libsignal.utilities.Snode
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days

class ClosedGroupPoller(
    private val scope: CoroutineScope,
    private val executor: CoroutineDispatcher,
    private val closedGroupSessionId: AccountId,
    private val configFactoryProtocol: ConfigFactoryProtocol,
    private val groupManagerV2: GroupManagerV2,
    private val storage: StorageProtocol,
    private val lokiApiDatabase: LokiAPIDatabaseProtocol,
) {
    companion object {
        private const val POLL_INTERVAL = 3_000L
        private const val POLL_ERROR_RETRY_DELAY = 10_000L

        private const val TAG = "ClosedGroupPoller"
    }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return // already started, don't restart

        Log.d(TAG, "Starting closed group poller for ${closedGroupSessionId.hexString.take(4)}")
        job?.cancel()
        job = scope.launch(executor) {
            while (isActive) {
                try {
                    val swarmNodes = SnodeAPI.getSwarm(closedGroupSessionId.hexString).await().toMutableSet()
                    var currentSnode: Snode? = null

                    while (isActive) {
                        if (currentSnode == null) {
                            check(swarmNodes.isNotEmpty()) { "No swarm nodes found" }
                            Log.d(TAG, "No current snode, getting a new one. Remaining in pool = ${swarmNodes.size - 1}")
                            currentSnode = swarmNodes.random()
                            swarmNodes.remove(currentSnode)
                        }

                        val result = runCatching { poll(currentSnode!!) }
                        when {
                            result.isSuccess -> {
                                delay(POLL_INTERVAL)
                            }

                            result.isFailure -> {
                                val error = result.exceptionOrNull()!!
                                if (error is CancellationException) {
                                    throw error
                                }

                                Log.e(TAG, "Error polling closed group", error)
                                // Clearing snode so we get a new one next time
                                currentSnode = null
                                delay(POLL_INTERVAL)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error during group poller", e)
                    delay(POLL_ERROR_RETRY_DELAY)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun poll(snode: Snode): Unit = coroutineScope {
        val groupAuth =
            configFactoryProtocol.getGroupAuth(closedGroupSessionId) ?: return@coroutineScope
        val configHashesToExtends = configFactoryProtocol.withGroupConfigs(closedGroupSessionId) {
            buildSet {
                addAll(it.groupKeys.currentHashes())
                addAll(it.groupInfo.currentHashes())
                addAll(it.groupMembers.currentHashes())
            }
        }

        val adminKey = requireNotNull(configFactoryProtocol.withUserConfigs {
            it.userGroups.getClosedGroup(closedGroupSessionId.hexString)
        }) {
            "Group doesn't exist"
        }.adminKey

        val pollingTasks = mutableListOf<Pair<String, Deferred<*>>>()

        pollingTasks += "retrieving revoked messages" to async {
            handleRevoked(
                SnodeAPI.sendBatchRequest(
                    snode,
                    closedGroupSessionId.hexString,
                    SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                        lastHash = lokiApiDatabase.getLastMessageHashValue(
                            snode,
                            closedGroupSessionId.hexString,
                            Namespace.REVOKED_GROUP_MESSAGES()
                        ).orEmpty(),
                        auth = groupAuth,
                        namespace = Namespace.REVOKED_GROUP_MESSAGES(),
                        maxSize = null,
                    ),
                    RetrieveMessageResponse::class.java
                )
            )
        }

        if (configHashesToExtends.isNotEmpty() && adminKey != null) {
            pollingTasks += "extending group config TTL" to async {
                SnodeAPI.sendBatchRequest(
                    snode,
                    closedGroupSessionId.hexString,
                    SnodeAPI.buildAuthenticatedAlterTtlBatchRequest(
                        messageHashes = configHashesToExtends.toList(),
                        auth = groupAuth,
                        newExpiry = SnodeAPI.nowWithOffset + 14.days.inWholeMilliseconds,
                        extend = true
                    ),
                )
            }
        }

        val groupMessageRetrieval = async {
            SnodeAPI.sendBatchRequest(
                snode = snode,
                publicKey = closedGroupSessionId.hexString,
                request = SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                    lastHash = lokiApiDatabase.getLastMessageHashValue(
                        snode,
                        closedGroupSessionId.hexString,
                        Namespace.CLOSED_GROUP_MESSAGES()
                    ).orEmpty(),
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
                    publicKey = closedGroupSessionId.hexString,
                    request = SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                        lastHash = lokiApiDatabase.getLastMessageHashValue(
                            snode,
                            closedGroupSessionId.hexString,
                            ns
                        ).orEmpty(),
                        auth = groupAuth,
                        namespace = ns,
                        maxSize = null,
                    ),
                    responseType = RetrieveMessageResponse::class.java
                )
            }
        }

        // The retrieval of the config and regular messages can be done concurrently,
        // however, in order for the messages to be able to be decrypted, the config messages
        // must be processed first.
        pollingTasks += "polling and handling group config keys and messages" to async {
            val (keysMessage, infoMessage, membersMessage) = groupConfigRetrieval.map { it.await() }
            saveLastMessageHash(snode, keysMessage, Namespace.ENCRYPTION_KEYS())
            saveLastMessageHash(snode, infoMessage, Namespace.CLOSED_GROUP_INFO())
            saveLastMessageHash(snode, membersMessage, Namespace.CLOSED_GROUP_MEMBERS())
            handleGroupConfigMessages(keysMessage, infoMessage, membersMessage)

            val regularMessages = groupMessageRetrieval.await()
            handleMessages(regularMessages, snode)
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

    private fun RetrieveMessageResponse.Message.toConfigMessage(): ConfigMessage {
        return ConfigMessage(hash, data, timestamp)
    }

    private fun saveLastMessageHash(snode: Snode, body: RetrieveMessageResponse, namespace: Int) {
        if (body.messages.isNotEmpty()) {
            lokiApiDatabase.setLastMessageHashValue(
                snode = snode,
                publicKey = closedGroupSessionId.hexString,
                newValue = body.messages.last().hash,
                namespace = namespace
            )
        }
    }

    private suspend fun handleRevoked(body: RetrieveMessageResponse) {
        body.messages.forEach { msg ->
            val decoded = configFactoryProtocol.maybeDecryptForUser(
                msg.data,
                Sodium.KICKED_DOMAIN,
                closedGroupSessionId,
            )

            if (decoded != null) {
                Log.d(TAG, "decoded kick message was for us")
                val message = decoded.decodeToString()
                if (Sodium.KICKED_REGEX.matches(message)) {
                    val (sessionId, generation) = message.split("-")
                    val currentKeysGeneration by lazy {
                        configFactoryProtocol.withGroupConfigs(closedGroupSessionId) {
                            it.groupKeys.currentGeneration()
                        }
                    }

                    if (sessionId == storage.getUserPublicKey() && generation.toInt() >= currentKeysGeneration) {
                        try {
                            groupManagerV2.handleKicked(closedGroupSessionId)
                        } catch (e: Exception) {
                            Log.e("GroupPoller", "Error handling kicked message: $e")
                        }
                    }
                }
            }
        }
    }

    private fun handleGroupConfigMessages(
        keysResponse: RetrieveMessageResponse,
        infoResponse: RetrieveMessageResponse,
        membersResponse: RetrieveMessageResponse
    ) {
        if (keysResponse.messages.isEmpty() && infoResponse.messages.isEmpty() && membersResponse.messages.isEmpty()) {
            return
        }

        Log.d(
            TAG, "Handling group config messages(" +
                    "info = ${infoResponse.messages.size}, " +
                    "keys = ${keysResponse.messages.size}, " +
                    "members = ${membersResponse.messages.size})"
        )

        configFactoryProtocol.mergeGroupConfigMessages(
            groupId = closedGroupSessionId,
            keys = keysResponse.messages.map { it.toConfigMessage() },
            info = infoResponse.messages.map { it.toConfigMessage() },
            members = membersResponse.messages.map { it.toConfigMessage() },
        )
    }

    private fun handleMessages(body: RawResponse, snode: Snode) {
        val messages = configFactoryProtocol.withGroupConfigs(closedGroupSessionId) {
            SnodeAPI.parseRawMessagesResponse(
                rawResponse = body,
                snode = snode,
                publicKey = closedGroupSessionId.hexString,
                decrypt = it.groupKeys::decrypt,
            )
        }

        val parameters = messages.map { (envelope, serverHash) ->
            MessageReceiveParameters(
                envelope.toByteArray(),
                serverHash = serverHash,
                closedGroup = Destination.ClosedGroup(closedGroupSessionId.hexString)
            )
        }

        parameters.chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER).forEach { chunk ->
            val job = BatchMessageReceiveJob(chunk)
            JobQueue.shared.add(job)
        }

        if (messages.isNotEmpty()) {
            Log.d(TAG, "namespace for messages rx count: ${messages.size}")
        }
    }
}
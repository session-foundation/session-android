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
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.Sodium
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.snode.RawResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
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
) {

    data class ParsedRawMessage(
            val data: ByteArray,
            val hash: String,
            val timestamp: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ParsedRawMessage

            if (!data.contentEquals(other.data)) return false
            if (hash != other.hash) return false
            if (timestamp != other.timestamp) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + hash.hashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }

    companion object {
        const val POLL_INTERVAL = 3_000L
        const val ENABLE_LOGGING = false
    }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return // already started, don't restart

        if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Starting closed group poller for ${closedGroupSessionId.hexString.take(4)}")
        job?.cancel()
        job = scope.launch(executor) {
            while (isActive) {
                val group = configFactoryProtocol.withUserConfigs { it.userGroups.getClosedGroup(closedGroupSessionId.hexString) } ?: break
                val nextPoll = runCatching { poll(group) }
                when {
                    nextPoll.isFailure -> {
                        Log.e("ClosedGroupPoller", "Error polling closed group", nextPoll.exceptionOrNull())
                        delay(POLL_INTERVAL)
                    }

                    nextPoll.getOrNull() == null -> {
                        // assume null poll time means don't continue polling, either the group has been deleted or something else
                        if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Stopping the closed group poller")
                        break
                    }

                    else -> {
                        delay(nextPoll.getOrThrow()!!)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun poll(group: GroupInfo.ClosedGroupInfo): Long? = coroutineScope {
        val snode = SnodeAPI.getSingleTargetSnode(closedGroupSessionId.hexString).await()

        val groupAuth = configFactoryProtocol.getGroupAuth(closedGroupSessionId) ?: return@coroutineScope null
        val configHashesToExtends = configFactoryProtocol.withGroupConfigs(closedGroupSessionId) {
            buildSet {
                addAll(it.groupKeys.currentHashes())
                addAll(it.groupInfo.currentHashes())
                addAll(it.groupMembers.currentHashes())
            }
        }

        val adminKey = requireNotNull(configFactoryProtocol.withUserConfigs { it.userGroups.getClosedGroup(closedGroupSessionId.hexString) }) {
            "Group doesn't exist"
        }.adminKey

        val pollingTasks = mutableListOf<Pair<String, Deferred<*>>>()

        pollingTasks += "Poll revoked messages" to async {
            handleRevoked(
                SnodeAPI.sendBatchRequest(
                    snode,
                    closedGroupSessionId.hexString,
                    SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                        snode = snode,
                        auth = groupAuth,
                        namespace = Namespace.REVOKED_GROUP_MESSAGES(),
                        maxSize = null,
                    ),
                    Map::class.java
                )
            )
        }

        pollingTasks += "Poll group messages" to async {
            handleMessages(
                body = SnodeAPI.sendBatchRequest(
                    snode,
                    closedGroupSessionId.hexString,
                    SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                        snode = snode,
                        auth = groupAuth,
                        namespace = Namespace.CLOSED_GROUP_MESSAGES(),
                        maxSize = null,
                    ),
                    Map::class.java),
                snode = snode,
            )
        }

        pollingTasks += "Poll group keys config" to async {
            handleKeyPoll(
                response = SnodeAPI.sendBatchRequest(
                    snode,
                    closedGroupSessionId.hexString,
                    SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                        snode = snode,
                        auth = groupAuth,
                        namespace = Namespace.ENCRYPTION_KEYS(),
                        maxSize = null,
                    ),
                    Map::class.java),
            )
        }

        pollingTasks += "Poll group info config" to async {
            handleInfo(
                response = SnodeAPI.sendBatchRequest(
                    snode,
                    closedGroupSessionId.hexString,
                    SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                        snode = snode,
                        auth = groupAuth,
                        namespace = Namespace.CLOSED_GROUP_INFO(),
                        maxSize = null,
                    ),
                    Map::class.java),
            )
        }

        pollingTasks += "Poll group members config" to async {
            handleMembers(
                SnodeAPI.sendBatchRequest(
                    snode,
                    closedGroupSessionId.hexString,
                    SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                        snode = snode,
                        auth = groupAuth,
                        namespace = Namespace.CLOSED_GROUP_MEMBERS(),
                        maxSize = null,
                    ),
                    Map::class.java),
            )
        }

        if (configHashesToExtends.isNotEmpty() && adminKey != null) {
            pollingTasks += "Extend group config TTL" to async {
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

        val errors = pollingTasks.mapNotNull { (name, task) ->
            runCatching { task.await() }
                .exceptionOrNull()
                ?.takeIf { it !is CancellationException }
                ?.let { RuntimeException("Error executing: $name", it) }
        }

        if (errors.isNotEmpty()) {
            throw PollerException("Error polling closed group", errors)
        }

        POLL_INTERVAL // this might change in future
    }

    private fun parseMessages(body: RawResponse): List<ParsedRawMessage> {
        val messages = body["messages"] as? List<*> ?: return emptyList()
        return messages.mapNotNull { messageMap ->
            val rawMessageAsJSON = messageMap as? Map<*, *> ?: return@mapNotNull null
            val base64EncodedData = rawMessageAsJSON["data"] as? String ?: return@mapNotNull null
            val hash = rawMessageAsJSON["hash"] as? String ?: return@mapNotNull null
            val timestamp = rawMessageAsJSON["timestamp"] as? Long ?: return@mapNotNull null
            val data = base64EncodedData.let { Base64.decode(it) }
            ParsedRawMessage(data, hash, timestamp)
        }
    }

    private suspend fun handleRevoked(body: RawResponse) {
        // This shouldn't ever return null at this point
        val messages = body["messages"] as? List<*>
            ?: return Log.w("GroupPoller", "body didn't contain a list of messages")
        messages.forEach { messageMap ->
            val rawMessageAsJSON = messageMap as? Map<*,*>
                ?: return@forEach Log.w("GroupPoller", "rawMessage wasn't a map as expected")
            val data = rawMessageAsJSON["data"] as? String ?: return@forEach
            val hash = rawMessageAsJSON["hash"] as? String ?: return@forEach
            val timestamp = rawMessageAsJSON["timestamp"] as? Long ?: return@forEach
            Log.d("GroupPoller", "Handling message with hash $hash")

            val decoded = configFactoryProtocol.maybeDecryptForUser(
                Base64.decode(data),
                Sodium.KICKED_DOMAIN,
                closedGroupSessionId,
            )

            if (decoded != null) {
                Log.d("GroupPoller", "decoded kick message was for us")
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

    private fun handleKeyPoll(response: RawResponse) {
        // get all the data to hash objects and process them
        val allMessages = parseMessages(response)
        if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Total key messages this poll: ${allMessages.size}")
        var total = 0
        allMessages.forEach { (message, hash, timestamp) ->
            configFactoryProtocol.withMutableGroupConfigs(closedGroupSessionId) { configs ->
                if (configs.loadKeys(message, hash, timestamp)) {
                    total++
                }
            }

            if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Merged $hash for keys on ${closedGroupSessionId.hexString}")
        }
        if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Total key messages consumed: $total")
    }

    private fun handleInfo(response: RawResponse) {
        val messages = parseMessages(response)
        messages.forEach { (message, hash, _) ->
            configFactoryProtocol.withMutableGroupConfigs(closedGroupSessionId) { configs ->
                configs.groupInfo.merge(arrayOf(hash to message))
            }
            if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Merged $hash for info on ${closedGroupSessionId.hexString}")
        }
    }

    private fun handleMembers(response: RawResponse) {
        parseMessages(response).forEach { (message, hash, _) ->
            configFactoryProtocol.withMutableGroupConfigs(closedGroupSessionId) { configs ->
                configs.groupMembers.merge(arrayOf(hash to message))
            }
            if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Merged $hash for members on ${closedGroupSessionId.hexString}")
        }
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

        if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "namespace for messages rx count: ${messages.size}")

    }

}
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
import network.loki.messenger.libsession_util.GroupInfoConfig
import network.loki.messenger.libsession_util.GroupKeysConfig
import network.loki.messenger.libsession_util.GroupMembersConfig
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.Sodium
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.snode.GroupSubAccountSwarmAuth
import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsession.snode.RawResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.model.BatchResponse
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withGroupConfigsOrNull
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
    private val groupManagerV2: GroupManagerV2) {

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
            val closedGroups = configFactoryProtocol.userGroups ?: return@launch
            while (isActive) {
                val group = closedGroups.getClosedGroup(closedGroupSessionId.hexString) ?: break
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

        configFactoryProtocol.withGroupConfigsOrNull(closedGroupSessionId) { info, members, keys ->
            val hashesToExtend = mutableSetOf<String>()

            hashesToExtend += info.currentHashes()
            hashesToExtend += members.currentHashes()
            hashesToExtend += keys.currentHashes()

            val authData = group.authData
            val adminKey = group.adminKey
            val groupAccountId = group.groupAccountId
            val auth = if (authData != null) {
                GroupSubAccountSwarmAuth(
                    groupKeysConfig = keys,
                    accountId = groupAccountId,
                    authData = authData
                )
            } else if (adminKey != null) {
                OwnedSwarmAuth.ofClosedGroup(
                    groupAccountId = groupAccountId,
                    adminKey = adminKey
                )
            } else {
                Log.e("ClosedGroupPoller", "No auth data for group, polling is cancelled")
                return@coroutineScope null
            }

            val pollingTasks = mutableListOf<Pair<String, Deferred<*>>>()

            pollingTasks += "Poll revoked messages" to async {
                handleRevoked(
                    body = SnodeAPI.sendBatchRequest(
                        groupAccountId,
                        SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                            snode = snode,
                            auth = auth,
                            namespace = Namespace.REVOKED_GROUP_MESSAGES(),
                            maxSize = null,
                        ),
                        Map::class.java),
                    keys = keys
                )
            }

            pollingTasks += "Poll group messages" to async {
                handleMessages(
                    body = SnodeAPI.sendBatchRequest(
                        groupAccountId,
                        SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                            snode = snode,
                            auth = auth,
                            namespace = Namespace.CLOSED_GROUP_MESSAGES(),
                            maxSize = null,
                        ),
                        Map::class.java),
                    snode = snode,
                    keysConfig = keys
                )
            }

            pollingTasks += "Poll group keys config" to async {
                handleKeyPoll(
                    response = SnodeAPI.sendBatchRequest(
                        groupAccountId,
                        SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                            snode = snode,
                            auth = auth,
                            namespace = keys.namespace(),
                            maxSize = null,
                        ),
                        Map::class.java),
                    keysConfig = keys,
                    infoConfig = info,
                    membersConfig = members
                )
            }

            pollingTasks += "Poll group info config" to async {
                handleInfo(
                    response = SnodeAPI.sendBatchRequest(
                        groupAccountId,
                        SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                            snode = snode,
                            auth = auth,
                            namespace = Namespace.CLOSED_GROUP_INFO(),
                            maxSize = null,
                        ),
                        Map::class.java),
                    infoConfig = info
                )
            }

            pollingTasks += "Poll group members config" to async {
                handleMembers(
                    response = SnodeAPI.sendBatchRequest(
                        groupAccountId,
                        SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                            snode = snode,
                            auth = auth,
                            namespace = Namespace.CLOSED_GROUP_MEMBERS(),
                            maxSize = null,
                        ),
                        Map::class.java),
                    membersConfig = members
                )
            }

            if (hashesToExtend.isNotEmpty() && adminKey != null) {
                pollingTasks += "Extend group config TTL" to async {
                    SnodeAPI.sendBatchRequest(
                        groupAccountId,
                        SnodeAPI.buildAuthenticatedAlterTtlBatchRequest(
                            messageHashes = hashesToExtend.toList(),
                            auth = auth,
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

            // If we no longer have a group, stop poller
            if (configFactoryProtocol.userGroups?.getClosedGroup(closedGroupSessionId.hexString) == null) return@coroutineScope null

            // if poll result body is null here we don't have any things ig
            if (ENABLE_LOGGING) Log.d(
                "ClosedGroupPoller",
                "Poll results @${SnodeAPI.nowWithOffset}:"
            )

            val requiresSync =
                info.needsPush() || members.needsPush() || keys.needsRekey() || keys.pendingConfig() != null

            if (info.needsDump() || members.needsDump() || keys.needsDump()) {
                configFactoryProtocol.saveGroupConfigs(keys, info, members)
            }

            if (requiresSync) {
                configFactoryProtocol.scheduleUpdate(Destination.ClosedGroup(closedGroupSessionId.hexString))
            }
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

    private suspend fun handleRevoked(body: RawResponse, keys: GroupKeysConfig) {
        // This shouldn't ever return null at this point
        val userSessionId = configFactoryProtocol.userSessionId()!!
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
                    if (sessionId == userSessionId.hexString && generation.toInt() >= keys.currentGeneration()) {
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

    private fun handleKeyPoll(response: RawResponse,
                              keysConfig: GroupKeysConfig,
                              infoConfig: GroupInfoConfig,
                              membersConfig: GroupMembersConfig) {
        // get all the data to hash objects and process them
        val allMessages = parseMessages(response)
        if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Total key messages this poll: ${allMessages.size}")
        var total = 0
        allMessages.forEach { (message, hash, timestamp) ->
            if (keysConfig.loadKey(message, hash, timestamp, infoConfig, membersConfig)) {
                total++
            }
            if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Merged $hash for keys on ${closedGroupSessionId.hexString}")
        }
        if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Total key messages consumed: $total")
    }

    private fun handleInfo(response: RawResponse,
                           infoConfig: GroupInfoConfig) {
        val messages = parseMessages(response)
        messages.forEach { (message, hash, _) ->
            infoConfig.merge(hash to message)
            if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Merged $hash for info on ${closedGroupSessionId.hexString}")
        }
        if (messages.isNotEmpty()) {
            val lastTimestamp = messages.maxOf { it.timestamp }
            MessagingModuleConfiguration.shared.storage.notifyConfigUpdates(infoConfig, lastTimestamp)
        }
    }

    private fun handleMembers(response: RawResponse,
                              membersConfig: GroupMembersConfig) {
        parseMessages(response).forEach { (message, hash, _) ->
            membersConfig.merge(hash to message)
            if (ENABLE_LOGGING) Log.d("ClosedGroupPoller", "Merged $hash for members on ${closedGroupSessionId.hexString}")
        }
    }

    private fun handleMessages(body: RawResponse, snode: Snode, keysConfig: GroupKeysConfig) {
        val messages = SnodeAPI.parseRawMessagesResponse(
            rawResponse = body,
            snode = snode,
            publicKey = closedGroupSessionId.hexString,
            decrypt = keysConfig::decrypt
        )

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
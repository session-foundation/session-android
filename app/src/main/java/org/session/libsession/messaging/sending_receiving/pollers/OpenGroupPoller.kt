package org.session.libsession.messaging.sending_receiving.pollers

import com.fasterxml.jackson.core.type.TypeReference
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.OpenGroupDeleteJob
import org.session.libsession.messaging.jobs.TrimThreadJob
import org.session.libsession.messaging.messages.Message.Companion.senderOrSync
import org.session.libsession.messaging.open_groups.Endpoint
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupApi.BatchRequest
import org.session.libsession.messaging.open_groups.OpenGroupApi.BatchRequestInfo
import org.session.libsession.messaging.open_groups.OpenGroupApi.BatchResponse
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capability
import org.session.libsession.messaging.open_groups.OpenGroupApi.DirectMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi.getOrFetchServerCapabilities
import org.session.libsession.messaging.open_groups.OpenGroupApi.parallelBatch
import org.session.libsession.messaging.sending_receiving.MessageParser
import org.session.libsession.messaging.sending_receiving.ReceivedMessageProcessor
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.HTTP.Verb.GET
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.CommunityDatabase
import org.thoughtcrime.securesms.util.AppVisibilityManager

private typealias PollRequestToken = Channel<Result<List<String>>>

/**
 * A [OpenGroupPoller] is responsible for polling all communities on a particular server.
 *
 * Once this class is created, it will start polling when the app becomes visible (and stop whe
 * the app becomes invisible), it will also respond to manual poll requests regardless of the app visibility.
 *
 * To stop polling, you can cancel the [CoroutineScope] that was passed to the constructor.
 */
class OpenGroupPoller @AssistedInject constructor(
    private val storage: StorageProtocol,
    private val appVisibilityManager: AppVisibilityManager,
    private val configFactory: ConfigFactoryProtocol,
    private val trimThreadJobFactory: TrimThreadJob.Factory,
    private val openGroupDeleteJobFactory: OpenGroupDeleteJob.Factory,
    private val communityDatabase: CommunityDatabase,
    private val receivedMessageProcessor: ReceivedMessageProcessor,
    private val messageParser: MessageParser,
    @Assisted private val server: String,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val pollerSemaphore: Semaphore,
) {
    companion object {
        private const val POLL_INTERVAL_MILLS: Long = 4000L
        const val MAX_INACTIVITIY_PERIOD_MILLS = 14 * 24 * 60 * 60 * 1000L // 14 days

        private const val TAG = "OpenGroupPoller"
    }

    private val pendingPollRequest = Channel<PollRequestToken>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pollState: StateFlow<PollState> = flow {
        val tokens = arrayListOf<PollRequestToken>()

        while (true) {
            // Wait for next request(s) to come in
            tokens.clear()
            tokens.add(pendingPollRequest.receive())
            tokens.addAll(generateSequence { pendingPollRequest.tryReceive().getOrNull() })

            Log.d(TAG, "Polling open group messages for server: $server")
            emit(PollState.Polling)
            val pollResult = runCatching {
                pollerSemaphore.withPermit {
                    pollOnce()
                }
            }
            tokens.forEach { it.trySend(pollResult) }
            emit(PollState.Idle(pollResult))

            pollResult.exceptionOrNull()?.let {
                Log.e(TAG, "Error while polling open groups for $server", it)
            }

        }
    }.stateIn(scope, SharingStarted.Eagerly, PollState.Idle(null))

    init {
        // Start a periodic polling request when the app becomes visible
        scope.launch {
            appVisibilityManager.isAppVisible
                .collectLatest { visible ->
                    if (visible) {
                        while (true) {
                            val r = requestPollAndAwait()
                            if (r.isSuccess) {
                                delay(POLL_INTERVAL_MILLS)
                            } else {
                                delay(2000L)
                            }
                        }
                    }
                }
        }
    }

    /**
     * Requests a poll and await for the result.
     *
     * The result will be a list of room tokens that were polled.
     */
    suspend fun requestPollAndAwait(): Result<List<String>> {
        val token: PollRequestToken = Channel()
        pendingPollRequest.send(token)
        return token.receive()
    }

    private fun handleRoomPollInfo(
        address: Address.Community,
        pollInfoJson: Map<*, *>,
    ) {
        communityDatabase.patchRoomInfo(address, JsonUtil.toJson(pollInfoJson))
    }


    /**
     * Polls the open groups on the server once.
     *
     * @return A list of rooms that were polled.
     */
    private suspend fun pollOnce(): List<String> {
        val allCommunities = configFactory.withUserConfigs { it.userGroups.allCommunityInfo() }

        val rooms = allCommunities
            .mapNotNull { c -> c.community.takeIf { it.baseUrl == server }?.room }

        if (rooms.isEmpty()) {
            return emptyList()
        }

        poll(rooms)
            .asSequence()
            .filterNot { it.body == null }
            .forEach { response ->
                when (response.endpoint) {
                    is Endpoint.RoomPollInfo -> {
                        handleRoomPollInfo(Address.Community(server, response.endpoint.roomToken), response.body as Map<*, *>)
                    }
                    is Endpoint.RoomMessagesRecent -> {
                        handleMessages(response.endpoint.roomToken, response.body as List<OpenGroupApi.Message>)
                    }
                    is Endpoint.RoomMessagesSince  -> {
                        handleMessages(response.endpoint.roomToken, response.body as List<OpenGroupApi.Message>)
                    }
                    is Endpoint.Inbox, is Endpoint.InboxSince -> {
                        handleInboxMessages( response.body as List<DirectMessage>)
                    }
                    is Endpoint.Outbox, is Endpoint.OutboxSince -> {
                        handleOutboxMessages( response.body as List<DirectMessage>)
                    }
                    else -> { /* We don't care about the result of any other calls (won't be polled for) */}
                }
            }

        return rooms
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun poll(rooms: List<String>): List<BatchResponse<*>> {
        val lastInboxMessageId = storage.getLastInboxMessageId(server)
        val lastOutboxMessageId = storage.getLastOutboxMessageId(server)
        val requests = mutableListOf<BatchRequestInfo<*>>()

        val serverCapabilities = getOrFetchServerCapabilities(server)

        rooms.forEach { room ->
            val address = Address.Community(serverUrl = server, room = room)
            val latestRoomPollInfo = communityDatabase.getRoomInfo(address)
            val infoUpdates = latestRoomPollInfo?.details?.infoUpdates ?: 0
            val lastMessageServerId = storage.getLastMessageServerID(room, server) ?: 0L
            requests.add(
                BatchRequestInfo(
                    request = BatchRequest(
                        method = GET,
                        path = "/room/$room/pollInfo/$infoUpdates"
                    ),
                    endpoint = Endpoint.RoomPollInfo(room, infoUpdates),
                    responseType = object : TypeReference<Map<*, *>>(){}
                )
            )
            requests.add(
                if (lastMessageServerId == 0L) {
                    BatchRequestInfo(
                        request = BatchRequest(
                            method = GET,
                            path = "/room/$room/messages/recent?t=r&reactors=5"
                        ),
                        endpoint = Endpoint.RoomMessagesRecent(room),
                        responseType = object : TypeReference<List<OpenGroupApi.Message>>(){}
                    )
                } else {
                    BatchRequestInfo(
                        request = BatchRequest(
                            method = GET,
                            path = "/room/$room/messages/since/$lastMessageServerId?t=r&reactors=5"
                        ),
                        endpoint = Endpoint.RoomMessagesSince(room, lastMessageServerId),
                        responseType = object : TypeReference<List<OpenGroupApi.Message>>(){}
                    )
                }
            )
        }
        if (serverCapabilities.contains(Capability.BLIND.name.lowercase())) {
            if (storage.isCheckingCommunityRequests()) {
                requests.add(
                    if (lastInboxMessageId == null) {
                        BatchRequestInfo(
                            request = BatchRequest(
                                method = GET,
                                path = "/inbox"
                            ),
                            endpoint = Endpoint.Inbox,
                            responseType = object : TypeReference<List<DirectMessage>>() {}
                        )
                    } else {
                        BatchRequestInfo(
                            request = BatchRequest(
                                method = GET,
                                path = "/inbox/since/$lastInboxMessageId"
                            ),
                            endpoint = Endpoint.InboxSince(lastInboxMessageId),
                            responseType = object : TypeReference<List<DirectMessage>>() {}
                        )
                    }
                )
            }

            requests.add(
                if (lastOutboxMessageId == null) {
                    BatchRequestInfo(
                        request = BatchRequest(
                            method = GET,
                            path = "/outbox"
                        ),
                        endpoint = Endpoint.Outbox,
                        responseType = object : TypeReference<List<DirectMessage>>() {}
                    )
                } else {
                    BatchRequestInfo(
                        request = BatchRequest(
                            method = GET,
                            path = "/outbox/since/$lastOutboxMessageId"
                        ),
                        endpoint = Endpoint.OutboxSince(lastOutboxMessageId),
                        responseType = object : TypeReference<List<DirectMessage>>() {}
                    )
                }
            )
        }
        return parallelBatch(server, requests)
    }


    private fun handleMessages(
        roomToken: String,
        messages: List<OpenGroupApi.Message>
    ) {
        val (deletions, additions) = messages.partition { it.deleted }

        val threadAddress = Address.Community(serverUrl = server, room = roomToken)
        // check thread still exists
        val threadId = storage.getThreadId(threadAddress) ?: return

        if (additions.isNotEmpty()) {
            receivedMessageProcessor.startProcessing("CommunityPoller(${threadAddress.debugString})") { ctx ->
                for (msg in additions.sortedBy { it.seqno }) {
                    try {
                        // Set the last message server ID to each message as we process them, so that if processing fails halfway through,
                        // we don't re-process messages we've already handled.
                        storage.setLastMessageServerID(roomToken, server, msg.seqno)

                        receivedMessageProcessor.processCommunityMessage(
                            context = ctx,
                            threadAddress = threadAddress,
                            message = msg,
                        )
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Error processing open group message ${msg.id} in ${threadAddress.debugString}",
                            e
                        )
                    }
                }
            }

            JobQueue.shared.add(trimThreadJobFactory.create(threadId))
        }

        if (deletions.isNotEmpty()) {
            JobQueue.shared.add(
                openGroupDeleteJobFactory.create(
                    messageServerIds = LongArray(deletions.size) { i -> deletions[i].id },
                    threadId = threadId
                )
            )
        }
    }

    /**
     * Handle messages that are sent to us directly.
     */
    private fun handleInboxMessages(
        messages: List<DirectMessage>
    ) {
        if (messages.isEmpty()) return
        val sorted = messages.sortedBy { it.postedAt }

        val serverPubKeyHex = storage.getOpenGroupPublicKey(server)
            ?: run {
                Log.e(TAG, "No community server public key cannot process inbox messages")
                return
            }

        receivedMessageProcessor.startProcessing("CommunityInbox") { ctx ->
            for (apiMessage in sorted) {
                try {
                    storage.setLastInboxMessageId(server, sorted.last().id)

                    receivedMessageProcessor.processCommunityInboxMessage(
                        context = ctx,
                        message = apiMessage,
                        communityServerUrl = server,
                        communityServerPubKeyHex = serverPubKeyHex,
                    )

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing inbox message", e)
                }
            }
        }
    }

    /**
     * Handle messages that we have sent out to others.
     */
    private fun handleOutboxMessages(
        messages: List<DirectMessage>
    ) {
        if (messages.isEmpty()) return
        val sorted = messages.sortedBy { it.postedAt }

        val serverPubKeyHex = storage.getOpenGroupPublicKey(server)
            ?: run {
                Log.e(TAG, "No community server public key cannot process inbox messages")
                return
            }

        receivedMessageProcessor.startProcessing("CommunityOutbox") { ctx ->
            for (apiMessage in sorted) {
                try {
                    storage.setLastOutboxMessageId(server, sorted.last().id)

                    receivedMessageProcessor.processCommunityOutboxMessage(
                        context = ctx,
                        msg = apiMessage,
                        communityServerUrl = server,
                        communityServerPubKeyHex = serverPubKeyHex,
                    )

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing outbox message", e)
                }
            }
        }
    }


    sealed interface PollState {
        data class Idle(val lastPolled: Result<List<String>>?) : PollState
        data object Polling : PollState
    }

    @AssistedFactory
    interface Factory {
        fun create(server: String, scope: CoroutineScope, pollerSemaphore: Semaphore): OpenGroupPoller
    }
}
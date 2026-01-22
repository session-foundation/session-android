package org.session.libsession.messaging.sending_receiving.pollers

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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.OpenGroupDeleteJob
import org.session.libsession.messaging.jobs.TrimThreadJob
import org.session.libsession.messaging.open_groups.COMMUNITY_API_EXECUTOR_NAME
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capability
import org.session.libsession.messaging.open_groups.OpenGroupApi.DirectMessage
import org.session.libsession.messaging.open_groups.api.GetCapsApi
import org.session.libsession.messaging.open_groups.api.GetDirectMessagesApi
import org.session.libsession.messaging.open_groups.api.GetRoomMessagesApi
import org.session.libsession.messaging.open_groups.api.PollRoomApi
import org.session.libsession.messaging.sending_receiving.ReceivedMessageProcessor
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.database.CommunityDatabase
import org.thoughtcrime.securesms.util.AppVisibilityManager
import javax.inject.Named
import javax.inject.Provider

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
    @param:Named(COMMUNITY_API_EXECUTOR_NAME) private val communityApiExecutor: ServerApiExecutor,
    private val getRoomMessagesFactory: GetRoomMessagesApi.Factory,
    private val getDirectMessageFactory: GetDirectMessagesApi.Factory,
    private val pollRoomInfoFactory: PollRoomApi.Factory,
    private val getCapsApi: Provider<GetCapsApi>,
    private val json: Json,
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
        pollInfoJsonText: String,
    ) {
        communityDatabase.patchRoomInfo(address, pollInfoJsonText)
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

        val serverKey = allCommunities.firstOrNull {
            it.community.baseUrl == server
        }?.community?.pubKeyHex

        if (rooms.isEmpty() || serverKey.isNullOrBlank()) {
            return emptyList()
        }

        supervisorScope {
            var caps = storage.getServerCapabilities(server)
            if (caps == null) {
                val fetched = communityApiExecutor.execute(
                    ServerApiRequest(
                        serverBaseUrl = server,
                        serverX25519PubKeyHex = serverKey,
                        api = getCapsApi.get()
                    )
                )
                storage.setServerCapabilities(server, fetched.capabilities)
                caps = fetched.capabilities
            }

            for (room in rooms) {
                val address = Address.Community(serverUrl = server, room = room)
                val latestRoomPollInfo = communityDatabase.getRoomInfo(address)
                val infoUpdates = latestRoomPollInfo?.details?.infoUpdates ?: 0
                val lastMessageServerId = storage.getLastMessageServerID(room, server)

                // Poll room info
                launch {
                    val roomInfo = communityApiExecutor.execute(
                        ServerApiRequest(
                            serverBaseUrl = server,
                            serverX25519PubKeyHex = serverKey,
                            api = pollRoomInfoFactory.create(
                                room = room,
                                infoUpdates = infoUpdates
                            )
                        )
                    )

                    handleRoomPollInfo(
                        address = address,
                        pollInfoJsonText = json.encodeToString(roomInfo)
                    )
                }

                // Poll room messages
                launch {
                    val messages = communityApiExecutor.execute(
                        ServerApiRequest(
                            serverBaseUrl = server,
                            serverX25519PubKeyHex = serverKey,
                            api = getRoomMessagesFactory.create(
                                room = room,
                                sinceLastId = lastMessageServerId,
                            )
                        )
                    )

                    handleMessages(roomToken = room, messages = messages)
                }
            }

            // Handling direct messages only if blinded capability is supported
            if (caps.contains(Capability.BLIND.name.lowercase())) {
                // We'll only poll our index if we are accepting community requests
                if (storage.isCheckingCommunityRequests()) {
                    // Poll inbox messages
                    launch {
                        val inboxMessages = communityApiExecutor.execute(
                            ServerApiRequest(
                                serverBaseUrl = server,
                                serverX25519PubKeyHex = serverKey,
                                api = getDirectMessageFactory.create(
                                    inboxOrOutbox = true,
                                    sinceLastId = storage.getLastInboxMessageId(server),
                                )
                            )
                        )

                        handleInboxMessages(messages = inboxMessages)
                    }
                }

                // Poll outbox messages regardless because these are messages we sent
                launch {
                    val outboxMessages = communityApiExecutor.execute(
                        ServerApiRequest(
                            serverBaseUrl = server,
                            serverX25519PubKeyHex = serverKey,
                            api = getDirectMessageFactory.create(
                                inboxOrOutbox = false,
                                sinceLastId = storage.getLastOutboxMessageId(server),
                            )
                        )
                    )

                    handleOutboxMessages(messages = outboxMessages)
                }
            }
        }

        return rooms
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
        fun create(
            server: String,
            scope: CoroutineScope,
            pollerSemaphore: Semaphore
        ): OpenGroupPoller
    }
}
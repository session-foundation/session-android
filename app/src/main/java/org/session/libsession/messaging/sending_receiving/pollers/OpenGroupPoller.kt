package org.session.libsession.messaging.sending_receiving.pollers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.OpenGroupDeleteJob
import org.session.libsession.messaging.jobs.TrimThreadJob
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capability
import org.session.libsession.messaging.open_groups.OpenGroupApi.DirectMessage
import org.session.libsession.messaging.open_groups.api.CommunityApiExecutor
import org.session.libsession.messaging.open_groups.api.CommunityApiRequest
import org.session.libsession.messaging.open_groups.api.GetCapsApi
import org.session.libsession.messaging.open_groups.api.GetDirectMessagesApi
import org.session.libsession.messaging.open_groups.api.GetRoomMessagesApi
import org.session.libsession.messaging.open_groups.api.PollRoomApi
import org.session.libsession.messaging.open_groups.api.execute
import org.session.libsession.messaging.sending_receiving.ReceivedMessageProcessor
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.CommunityDatabase
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import javax.inject.Provider

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
    private val configFactory: ConfigFactoryProtocol,
    private val trimThreadJobFactory: TrimThreadJob.Factory,
    private val openGroupDeleteJobFactory: OpenGroupDeleteJob.Factory,
    private val communityDatabase: CommunityDatabase,
    private val receivedMessageProcessor: ReceivedMessageProcessor,
    private val communityApiExecutor: CommunityApiExecutor,
    private val getRoomMessagesFactory: GetRoomMessagesApi.Factory,
    private val getDirectMessageFactory: GetDirectMessagesApi.Factory,
    private val pollRoomInfoFactory: PollRoomApi.Factory,
    private val getCapsApi: Provider<GetCapsApi>,
    networkConnectivity: NetworkConnectivity,
    appVisibilityManager: AppVisibilityManager,
    private val json: Json,
    @Assisted private val server: String,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val pollerSemaphore: Semaphore,
): BasePoller<Unit>(
    networkConnectivity = networkConnectivity,
    scope = scope,
    appVisibilityManager = appVisibilityManager
) {
    override val successfulPollIntervalSeconds: Int
        get() = 4

    override val maxRetryIntervalSeconds: Int
        get() = 30

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
    override suspend fun doPollOnce(isFirstPollSinceAppStarted: Boolean): Unit = pollerSemaphore.withPermit {
        val allCommunities = configFactory.withUserConfigs { it.userGroups.allCommunityInfo() }

        val rooms = allCommunities
            .mapNotNull { c -> c.community.takeIf { it.baseUrl == server }?.room }

        val serverKey = allCommunities.firstOrNull {
            it.community.baseUrl == server
        }?.community?.pubKeyHex

        if (rooms.isEmpty() || serverKey.isNullOrBlank()) {
            return
        }

        supervisorScope {
            var caps = storage.getServerCapabilities(server)
            if (caps == null) {
                val fetched = communityApiExecutor.execute(
                    CommunityApiRequest(
                        serverBaseUrl = server,
                        serverPubKey = serverKey,
                        api = getCapsApi.get(),
                    )
                )
                storage.setServerCapabilities(server, fetched.capabilities)
                caps = fetched.capabilities
            }

            val allTasks = mutableListOf<Pair<String, Deferred<Unit>>>()

            for (room in rooms) {
                val address = Address.Community(serverUrl = server, room = room)
                val latestRoomPollInfo = communityDatabase.getRoomInfo(address)
                val infoUpdates = latestRoomPollInfo?.details?.infoUpdates ?: 0
                val lastMessageServerId = storage.getLastMessageServerID(room, server)

                // Poll room info
                allTasks += "polling room info" to async {
                    val roomInfo = communityApiExecutor.execute(
                        CommunityApiRequest(
                            serverBaseUrl = server,
                            serverPubKey = serverKey,
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
                allTasks += "polling room messages" to async {
                    val messages = communityApiExecutor.execute(
                        CommunityApiRequest(
                            serverBaseUrl = server,
                            serverPubKey = serverKey,
                            api = getRoomMessagesFactory.create(
                                room = room,
                                sinceSeqNo = lastMessageServerId,
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
                    allTasks += "polling inbox messages" to async {
                        val inboxMessages = communityApiExecutor.execute(
                            CommunityApiRequest(
                                serverBaseUrl = server,
                                serverPubKey = serverKey,
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
                allTasks += "polling outbox messages" to async {
                    val outboxMessages = communityApiExecutor.execute(
                        CommunityApiRequest(
                            serverBaseUrl = server,
                            serverPubKey = serverKey,
                            api = getDirectMessageFactory.create(
                                inboxOrOutbox = false,
                                sinceLastId = storage.getLastOutboxMessageId(server),
                            )
                        )
                    )

                    handleOutboxMessages(messages = outboxMessages)
                }
            }

            /**
             * Await on all tasks and gather the first exception with the rest errors suppressed.
             */
            val accumulatedError = allTasks
                .fold(null) { acc: Throwable?, (taskName, deferred) ->
                    val err = runCatching { deferred.await() }
                        .onFailure { if (it is CancellationException) throw it }
                        .exceptionOrNull()
                        ?.let { RuntimeException("Error $taskName", it) }

                    if (err != null) {
                        acc?.apply { addSuppressed(err) } ?: err
                    } else {
                        acc
                    }
                }

            if (accumulatedError != null) {
                throw accumulatedError
            }
        }
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
                            logTag,
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
                Log.e(logTag, "No community server public key cannot process inbox messages")
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
                    Log.e(logTag, "Error processing inbox message", e)
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
                Log.e(logTag, "No community server public key cannot process inbox messages")
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
                    Log.e(logTag, "Error processing outbox message", e)
                }
            }
        }
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
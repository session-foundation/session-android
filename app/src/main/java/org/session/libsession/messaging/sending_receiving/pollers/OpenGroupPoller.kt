package org.session.libsession.messaging.sending_receiving.pollers

import com.google.protobuf.ByteString
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
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.jobs.OpenGroupDeleteJob
import org.session.libsession.messaging.jobs.TrimThreadJob
import org.session.libsession.messaging.messages.Message.Companion.senderOrSync
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.Endpoint
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsession.messaging.sending_receiving.MessageReceiver
import org.session.libsession.messaging.sending_receiving.ReceivedMessageHandler
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.BlindMappingRepository
import org.thoughtcrime.securesms.database.GroupMemberDatabase
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.util.AppVisibilityManager
import java.util.concurrent.TimeUnit

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
    private val blindMappingRepository: BlindMappingRepository,
    private val receivedMessageHandler: ReceivedMessageHandler,
    private val batchMessageJobFactory: BatchMessageReceiveJob.Factory,
    private val groupMemberDatabase: GroupMemberDatabase,
    private val lokiThreadDatabase: LokiThreadDatabase,
    private val configFactory: ConfigFactoryProtocol,
    private val threadDatabase: ThreadDatabase,
    private val trimThreadJobFactory: TrimThreadJob.Factory,
    private val openGroupDeleteJobFactory: OpenGroupDeleteJob.Factory,
    @Assisted private val server: String,
    @Assisted private val scope: CoroutineScope,
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
            val pollResult = runCatching { pollOnce() }
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
        roomToken: String,
        pollInfo: OpenGroupApi.RoomPollInfo,
        publicKey: String
    ) {
        val existingOpenGroup = storage.getOpenGroup(roomToken, server)

        // If we don't have an existing group and don't have a 'createGroupIfMissingWithPublicKey'
        // value then don't process the poll info
        val publicKey = existingOpenGroup?.publicKey ?: publicKey
        val name = pollInfo.details?.name ?: existingOpenGroup?.name
        val infoUpdates = pollInfo.details?.infoUpdates ?: existingOpenGroup?.infoUpdates

        val openGroup = OpenGroup(
            server = server,
            room = pollInfo.token,
            name = name ?: "",
            description = (pollInfo.details?.description ?: existingOpenGroup?.description),
            publicKey = publicKey,
            imageId = (pollInfo.details?.imageId ?: existingOpenGroup?.imageId),
            canWrite = pollInfo.write,
            infoUpdates = infoUpdates ?: 0,
            isAdmin = pollInfo.admin,
            isModerator = pollInfo.moderator,
        )
        // - Open Group changes
        lokiThreadDatabase.setOpenGroupChat(
            openGroup = openGroup,
            threadID = threadDatabase.getThreadIdIfExistsFor(Address.Community(server, roomToken))
        )

        // - User Count
        storage.setUserCount(roomToken, server, pollInfo.activeUsers)

        val community = Address.Community(openGroup)

        // - Moderators
        pollInfo.details?.moderators?.let { list ->
            groupMemberDatabase.updateGroupMembers(
                community, GroupMemberRole.MODERATOR, list.map(::AccountId)
            )
        }
        pollInfo.details?.hiddenModerators?.let { list ->
            groupMemberDatabase.updateGroupMembers(
                community, GroupMemberRole.HIDDEN_MODERATOR, list.map(::AccountId)
            )
        }
        // - Admins
        pollInfo.details?.admins?.let { list ->
            groupMemberDatabase.updateGroupMembers(
                community, GroupMemberRole.ADMIN, list.map(::AccountId)
            )
        }
        pollInfo.details?.hiddenAdmins?.let { list ->
            groupMemberDatabase.updateGroupMembers(
                community, GroupMemberRole.HIDDEN_ADMIN, list.map(::AccountId)
            )
        }
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

        val publicKey = allCommunities.first { it.community.baseUrl == server }.community.pubKeyHex

        OpenGroupApi
            .poll(rooms, server)
            .asSequence()
            .filterNot { it.body == null }
            .forEach { response ->
                when (response.endpoint) {
                    is Endpoint.RoomPollInfo -> {
                        handleRoomPollInfo(  response.endpoint.roomToken, response.body as OpenGroupApi.RoomPollInfo, publicKey)
                    }
                    is Endpoint.RoomMessagesRecent -> {
                        handleMessages(server, response.endpoint.roomToken, response.body as List<OpenGroupApi.Message>)
                    }
                    is Endpoint.RoomMessagesSince  -> {
                        handleMessages(server, response.endpoint.roomToken, response.body as List<OpenGroupApi.Message>)
                    }
                    is Endpoint.Inbox, is Endpoint.InboxSince -> {
                        handleDirectMessages(server, false, response.body as List<OpenGroupApi.DirectMessage>)
                    }
                    is Endpoint.Outbox, is Endpoint.OutboxSince -> {
                        handleDirectMessages(server, true, response.body as List<OpenGroupApi.DirectMessage>)
                    }
                    else -> { /* We don't care about the result of any other calls (won't be polled for) */}
                }
            }

        return rooms
    }

    private fun handleMessages(
        server: String,
        roomToken: String,
        messages: List<OpenGroupApi.Message>
    ) {
        val sortedMessages = messages.sortedBy { it.seqno }
        sortedMessages.maxOfOrNull { it.seqno }?.let { seqNo ->
            storage.setLastMessageServerID(roomToken, server, seqNo)
            OpenGroupApi.pendingReactions.removeAll { !(it.seqNo == null || it.seqNo!! > seqNo) }
        }
        val (deletions, additions) = sortedMessages.partition { it.deleted }
        handleNewMessages(server, roomToken, additions.map {
            OpenGroupMessage(
                serverID = it.id,
                sender = it.sessionId,
                sentTimestamp = (it.posted * 1000).toLong(),
                base64EncodedData = it.data,
                base64EncodedSignature = it.signature,
                reactions = it.reactions
            )
        })
        handleDeletedMessages(server, roomToken, deletions.map { it.id })
    }

    private suspend fun handleDirectMessages(
        server: String,
        fromOutbox: Boolean,
        messages: List<OpenGroupApi.DirectMessage>
    ) {
        if (messages.isEmpty()) return
        val serverPublicKey = storage.getOpenGroupPublicKey(server)!!
        val sortedMessages = messages.sortedBy { it.id }
        val lastMessageId = sortedMessages.last().id
        if (fromOutbox) {
            storage.setLastOutboxMessageId(server, lastMessageId)
        } else {
            storage.setLastInboxMessageId(server, lastMessageId)
        }
        sortedMessages.forEach {
            val encodedMessage = Base64.decode(it.message)
            val envelope = SignalServiceProtos.Envelope.newBuilder()
                .setTimestampMs(TimeUnit.SECONDS.toMillis(it.postedAt))
                .setType(SignalServiceProtos.Envelope.Type.SESSION_MESSAGE)
                .setContent(ByteString.copyFrom(encodedMessage))
                .setSource(it.sender)
                .build()
            try {
                val (message, proto) = MessageReceiver.parse(
                    envelope.toByteArray(),
                    null,
                    fromOutbox,
                    if (fromOutbox) it.recipient else it.sender,
                    serverPublicKey,
                    emptySet() // this shouldn't be necessary as we are polling open groups here
                )
                if (fromOutbox) {
                    val syncTarget = blindMappingRepository.getMapping(
                        serverUrl = server,
                        blindedAddress = Address.Blinded(AccountId(it.recipient))
                    )?.accountId?.hexString ?: it.recipient

                    if (message is VisibleMessage) {
                        message.syncTarget = syncTarget
                    } else if (message is ExpirationTimerUpdate) {
                        message.syncTarget = syncTarget
                    }
                }
                val threadId = threadDatabase.getThreadIdIfExistsFor(message.senderOrSync.toAddress())
                receivedMessageHandler.handle(
                    message = message,
                    proto = proto,
                    threadId = threadId,
                    groupv2Id = null,
                    fromCommunity = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't handle direct message", e)
            }
        }
    }

    private fun handleNewMessages(server: String, roomToken: String, messages: List<OpenGroupMessage>) {
        val threadAddress = Address.Community(serverUrl = server, room = roomToken)
        // check thread still exists
        val threadId = storage.getThreadId(threadAddress) ?: return
        val envelopes =  mutableListOf<Triple<Long?, SignalServiceProtos.Envelope, Map<String, OpenGroupApi.Reaction>?>>()
        messages.sortedBy { it.serverID!! }.forEach { message ->
            if (!message.base64EncodedData.isNullOrEmpty()) {
                val envelope = SignalServiceProtos.Envelope.newBuilder()
                    .setType(SignalServiceProtos.Envelope.Type.SESSION_MESSAGE)
                    .setSource(message.sender!!)
                    .setSourceDevice(1)
                    .setContent(message.toProto().toByteString())
                    .setTimestampMs(message.sentTimestamp)
                    .build()
                envelopes.add(Triple( message.serverID, envelope, message.reactions))
            }
        }

        envelopes.chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER).forEach { list ->
            val parameters = list.map { (serverId, message, reactions) ->
                MessageReceiveParameters(message.toByteArray(), openGroupMessageServerID = serverId, reactions = reactions)
            }
            JobQueue.shared.add(batchMessageJobFactory.create(
                parameters,
                fromCommunity = threadAddress
            ))
        }

        if (envelopes.isNotEmpty()) {
            JobQueue.shared.add(trimThreadJobFactory.create(threadId))
        }
    }

    private fun handleDeletedMessages(server: String, roomToken: String, serverIds: List<Long>) {
        val threadID = storage.getThreadId(Address.Community(serverUrl = server, room = roomToken)) ?: return

        if (serverIds.isNotEmpty()) {
            JobQueue.shared.add(
                openGroupDeleteJobFactory.create(
                    messageServerIds = serverIds.toLongArray(),
                    threadId = threadID
                )
            )
        }
    }

    sealed interface PollState {
        data class Idle(val lastPolled: Result<List<String>>?) : PollState
        data object Polling : PollState
    }

    @AssistedFactory
    interface Factory {
        fun create(server: String, scope: CoroutineScope): OpenGroupPoller
    }
}
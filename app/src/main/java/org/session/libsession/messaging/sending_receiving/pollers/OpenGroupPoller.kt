package org.session.libsession.messaging.sending_receiving.pollers

import com.google.protobuf.ByteString
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import nl.komponents.kovenant.functional.map
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.BlindedIdMapping
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.GroupAvatarDownloadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.jobs.OpenGroupDeleteJob
import org.session.libsession.messaging.jobs.TrimThreadJob
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.Endpoint
import org.session.libsession.messaging.open_groups.GroupMember
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsession.messaging.sending_receiving.MessageReceiver
import org.session.libsession.messaging.sending_receiving.ReceivedMessageHandler
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupUtil
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.GroupMemberDatabase
import org.thoughtcrime.securesms.util.AppVisibilityManager
import java.util.concurrent.TimeUnit

private typealias ManualPollRequestToken = Channel<Result<Unit>>

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
    private val receivedMessageHandler: ReceivedMessageHandler,
    private val batchMessageJobFactory: BatchMessageReceiveJob.Factory,
    private val groupMemberDatabase: GroupMemberDatabase,
    @Assisted private val server: String,
    @Assisted private val scope: CoroutineScope,
) {
    private val mutableIsCaughtUp = MutableStateFlow(false)
    val isCaughtUp: StateFlow<Boolean> get() = mutableIsCaughtUp

    private val manualPollRequest = Channel<ManualPollRequestToken>()

    companion object {
        private const val POLL_INTERVAL_MILLS: Long = 4000L
        const val MAX_INACTIVITIY_PERIOD_MILLS = 14 * 24 * 60 * 60 * 1000L // 14 days

        private const val TAG = "OpenGroupPoller"

        fun handleRoomPollInfo(
            storage: StorageProtocol,
            memberDb: GroupMemberDatabase,
            server: String,
            roomToken: String,
            pollInfo: OpenGroupApi.RoomPollInfo,
            createGroupIfMissingWithPublicKey: String? = null
        ) {
            val groupId = "$server.$roomToken"
            val dbGroupId = GroupUtil.getEncodedOpenGroupID(groupId.toByteArray())
            val existingOpenGroup = storage.getOpenGroup(roomToken, server)

            // If we don't have an existing group and don't have a 'createGroupIfMissingWithPublicKey'
            // value then don't process the poll info
            val publicKey = existingOpenGroup?.publicKey ?: createGroupIfMissingWithPublicKey
            val name = pollInfo.details?.name ?: existingOpenGroup?.name
            val infoUpdates = pollInfo.details?.infoUpdates ?: existingOpenGroup?.infoUpdates

            if (publicKey == null) return

            val openGroup = OpenGroup(
                server = server,
                room = pollInfo.token,
                name = name ?: "",
                description = (pollInfo.details?.description ?: existingOpenGroup?.description),
                publicKey = publicKey,
                imageId = (pollInfo.details?.imageId ?: existingOpenGroup?.imageId),
                canWrite = pollInfo.write,
                infoUpdates = infoUpdates ?: 0
            )
            // - Open Group changes
            storage.updateOpenGroup(openGroup)

            // - User Count
            storage.setUserCount(roomToken, server, pollInfo.activeUsers)

            // - Moderators
            pollInfo.details?.moderators?.let { moderatorList ->
                memberDb.updateGroupMembers(
                    groupId, GroupMemberRole.MODERATOR, moderatorList
                )
            }
            pollInfo.details?.hiddenModerators?.let { moderatorList ->
                memberDb.updateGroupMembers(
                    groupId, GroupMemberRole.HIDDEN_MODERATOR, moderatorList
                )
            }
            // - Admins
            pollInfo.details?.admins?.let { moderatorList ->
                memberDb.updateGroupMembers(
                    groupId, GroupMemberRole.ADMIN, moderatorList
                )
            }
            pollInfo.details?.hiddenAdmins?.let { moderatorList ->
                memberDb.updateGroupMembers(
                    groupId, GroupMemberRole.HIDDEN_ADMIN, moderatorList
                )
            }

            // Update the group avatar
            if (
                (
                    pollInfo.details != null &&
                        pollInfo.details.imageId != null && (
                        pollInfo.details.imageId != existingOpenGroup?.imageId ||
                            !storage.hasDownloadedProfilePicture(dbGroupId)
                        ) &&
                        storage.getGroupAvatarDownloadJob(openGroup.server, openGroup.room, pollInfo.details.imageId) == null
                    ) || (
                    pollInfo.details == null &&
                        existingOpenGroup?.imageId != null &&
                        !storage.hasDownloadedProfilePicture(dbGroupId) &&
                        storage.getGroupAvatarDownloadJob(openGroup.server, openGroup.room, existingOpenGroup.imageId) == null
                    )
            ) {
                JobQueue.shared.add(GroupAvatarDownloadJob(server, roomToken, openGroup.imageId))
            }
            else if (
                pollInfo.details != null &&
                pollInfo.details.imageId == null &&
                existingOpenGroup?.imageId != null
            ) {
                storage.removeProfilePicture(dbGroupId)
            }
        }
    }

    init {
        scope.launch {
            while (true) {
                // Wait until the app is visible before starting the polling,
                // or when we receive a manual poll request
                val token = merge(
                    appVisibilityManager.isAppVisible.filter { it }.map { null },
                    manualPollRequest.receiveAsFlow()
                ).first()

                // We might have more than one manual poll request, collect them all now so
                // they don't trigger unnecessary pollings
                val extraTokens = buildList {
                    while (true) {
                        val nexToken = manualPollRequest.tryReceive().getOrNull() ?: break
                        add(nexToken)
                    }
                }

                mutableIsCaughtUp.value = false
                var delayDuration = POLL_INTERVAL_MILLS
                try {
                    Log.d(TAG, "Polling open group messages for server: $server")
                    pollOnce()
                    mutableIsCaughtUp.value = true
                    token?.trySend(Result.success(Unit))
                    extraTokens.forEach { it.trySend(Result.success(Unit)) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error while polling open group messages", e)
                    delayDuration = 2000L
                    token?.trySend(Result.failure(e))
                }

                delay(delayDuration)
            }
        }
    }

    private suspend fun pollOnce(isPostCapabilitiesRetry: Boolean = false) {
        val rooms = storage.getAllOpenGroups()
            .values
            .asSequence()
            .filter { it.server == server }
            .map { it.room }
            .toList()

        try {
            OpenGroupApi
                .poll(rooms, server)
                .await()
                .asSequence()
                .filterNot { it.body == null }
                .forEach { response ->
                    when (response.endpoint) {
                        is Endpoint.Capabilities -> {
                            handleCapabilities(server, response.body as OpenGroupApi.Capabilities)
                        }
                        is Endpoint.RoomPollInfo -> {
                            handleRoomPollInfo(storage, groupMemberDatabase, server, response.endpoint.roomToken, response.body as OpenGroupApi.RoomPollInfo)
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
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "Error while polling open group messages", e)
                updateCapabilitiesIfNeeded(isPostCapabilitiesRetry, e)
            }

            throw e
        }
    }

    suspend fun requestPollOnceAndWait() {
        val token = Channel<Result<Unit>>()
        manualPollRequest.send(token)
        token.receive().getOrThrow()
    }

    fun requestPollOnce() {
        scope.launch {
            manualPollRequest.send(Channel())
        }
    }

    private fun updateCapabilitiesIfNeeded(isPostCapabilitiesRetry: Boolean, exception: Exception) {
        if (exception is OnionRequestAPI.HTTPRequestFailedBlindingRequiredException) {
            if (!isPostCapabilitiesRetry) {
                OpenGroupApi.getCapabilities(server).map {
                    handleCapabilities(server, it)
                }
            }
        }
    }

    private fun handleCapabilities(server: String, capabilities: OpenGroupApi.Capabilities) {
        storage.setServerCapabilities(server, capabilities.capabilities)
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

    private fun handleDirectMessages(
        server: String,
        fromOutbox: Boolean,
        messages: List<OpenGroupApi.DirectMessage>
    ) {
        if (messages.isEmpty()) return
        val serverPublicKey = storage.getOpenGroupPublicKey(server)!!
        val sortedMessages = messages.sortedBy { it.id }
        val lastMessageId = sortedMessages.last().id
        val mappingCache = mutableMapOf<String, BlindedIdMapping>()
        if (fromOutbox) {
            storage.setLastOutboxMessageId(server, lastMessageId)
        } else {
            storage.setLastInboxMessageId(server, lastMessageId)
        }
        sortedMessages.forEach {
            val encodedMessage = Base64.decode(it.message)
            val envelope = SignalServiceProtos.Envelope.newBuilder()
                .setTimestamp(TimeUnit.SECONDS.toMillis(it.postedAt))
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
                    val mapping = mappingCache[it.recipient] ?: storage.getOrCreateBlindedIdMapping(
                        it.recipient,
                        server,
                        serverPublicKey,
                        true
                    )
                    val syncTarget = mapping.accountId ?: it.recipient
                    if (message is VisibleMessage) {
                        message.syncTarget = syncTarget
                    } else if (message is ExpirationTimerUpdate) {
                        message.syncTarget = syncTarget
                    }
                    mappingCache[it.recipient] = mapping
                }
                val threadId = Message.getThreadId(message, null, storage, false)
                receivedMessageHandler.handle(message, proto, threadId ?: -1, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't handle direct message", e)
            }
        }
    }

    private fun handleNewMessages(server: String, roomToken: String, messages: List<OpenGroupMessage>) {
        val openGroupID = "$server.$roomToken"
        val groupID = GroupUtil.getEncodedOpenGroupID(openGroupID.toByteArray())
        // check thread still exists
        val threadId = storage.getThreadId(Address.fromSerialized(groupID)) ?: -1
        val threadExists = threadId >= 0
        if (!threadExists) { return }
        val envelopes =  mutableListOf<Triple<Long?, SignalServiceProtos.Envelope, Map<String, OpenGroupApi.Reaction>?>>()
        messages.sortedBy { it.serverID!! }.forEach { message ->
            if (!message.base64EncodedData.isNullOrEmpty()) {
                val envelope = SignalServiceProtos.Envelope.newBuilder()
                    .setType(SignalServiceProtos.Envelope.Type.SESSION_MESSAGE)
                    .setSource(message.sender!!)
                    .setSourceDevice(1)
                    .setContent(message.toProto().toByteString())
                    .setTimestamp(message.sentTimestamp)
                    .build()
                envelopes.add(Triple( message.serverID, envelope, message.reactions))
            }
        }

        envelopes.chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER).forEach { list ->
            val parameters = list.map { (serverId, message, reactions) ->
                MessageReceiveParameters(message.toByteArray(), openGroupMessageServerID = serverId, reactions = reactions)
            }
            JobQueue.shared.add(batchMessageJobFactory.create(parameters, openGroupID))
        }

        if (envelopes.isNotEmpty()) {
            JobQueue.shared.add(TrimThreadJob(threadId, openGroupID))
        }
    }

    private fun handleDeletedMessages(server: String, roomToken: String, serverIds: List<Long>) {
        val openGroupId = "$server.$roomToken"
        val groupID = GroupUtil.getEncodedOpenGroupID(openGroupId.toByteArray())
        val threadID = storage.getThreadId(Address.fromSerialized(groupID)) ?: return

        if (serverIds.isNotEmpty()) {
            val deleteJob = OpenGroupDeleteJob(serverIds.toLongArray(), threadID, openGroupId)
            JobQueue.shared.add(deleteJob)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(server: String, scope: CoroutineScope): OpenGroupPoller
    }
}
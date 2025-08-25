package org.session.libsession.messaging.sending_receiving

import android.content.Context
import android.text.TextUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.messages.ExpirationConfiguration.Companion.isNewConfigEnabled
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.messages.control.DataExtractionNotification
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.messages.control.TypingIndicator
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.visible.Attachment
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.messaging.sending_receiving.notifications.PushRegistryV1
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.messaging.utilities.MessageAuthentication.buildDeleteMemberContentSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildGroupInviteSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildInfoChangeSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildMemberChangeSignature
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.GroupUtil.doubleEncodeGroupID
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.recipients.MessageType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.getType
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.database.ConfigDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.sskenvironment.ReadReceiptManager
import java.security.MessageDigest
import java.security.SignatureException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

internal fun MessageReceiver.isBlocked(publicKey: String): Boolean {
    val context = MessagingModuleConfiguration.shared.context
    val recipient = Recipient.from(context, Address.fromSerialized(publicKey), false)
    return recipient.isBlocked
}

@Singleton
class ReceivedMessageHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val storage: StorageProtocol,
    private val readReceiptManager: ReadReceiptManager,
    private val typingIndicators: SSKEnvironment.TypingIndicatorsProtocol,
    private val messageDataProvider: MessageDataProvider,
    private val messageExpirationManager: SSKEnvironment.MessageExpirationManagerProtocol,
    private val notificationManager: MessageNotifier,
    private val groupManagerV2: GroupManagerV2,
    private val proStatusManager: ProStatusManager,
    private val profileManager: SSKEnvironment.ProfileManagerProtocol,
    private val visibleMessageContextFactory: VisibleMessageHandlerContext.Factory,
    private val attachmentDownloadJobFactory: AttachmentDownloadJob.Factory
) {
    fun handle(message: Message, proto: SignalServiceProtos.Content, threadId: Long, openGroupID: String?, groupv2Id: AccountId?) {
        // Do nothing if the message was outdated
        if (messageIsOutdated(message, threadId, openGroupID)) { return }

        when (message) {
            is ReadReceipt -> handleReadReceipt(message)
            is TypingIndicator -> handleTypingIndicator(message)
            is GroupUpdated -> MessageReceiver.handleGroupUpdated(message, groupv2Id)
            is ExpirationTimerUpdate -> {
                // For groupsv2, there are dedicated mechanisms for handling expiration timers, and
                // we want to avoid the 1-to-1 message format which is unauthenticated in a group settings.
                if (groupv2Id != null) {
                    Log.d("MessageReceiver", "Ignoring expiration timer update for closed group")
                } // also ignore it for communities since they do not support disappearing messages
                else if (openGroupID != null) {
                    Log.d("MessageReceiver", "Ignoring expiration timer update for communities")
                } else {
                    handleExpirationTimerUpdate(message)
                }
            }
            is DataExtractionNotification -> handleDataExtractionNotification(message)
            is UnsendRequest -> handleUnsendRequest(message)
            is MessageRequestResponse -> handleMessageRequestResponse(message)
            is VisibleMessage -> handleVisibleMessage(
                message = message,
                proto = proto,
                context = visibleMessageContextFactory.create(threadId, openGroupID),
                runThreadUpdate = true,
                runProfileUpdate = true
            )
            is CallMessage -> handleCallMessage(message)
        }
    }

    private fun messageIsOutdated(message: Message, threadId: Long, openGroupID: String?): Boolean {
        when (message) {
            is ReadReceipt -> return false // No visible artifact created so better to keep for more reliable read states
            is UnsendRequest -> return false // We should always process the removal of messages just in case
        }

        // Determine the state of the conversation and the validity of the message
        val userPublicKey = storage.getUserPublicKey()!!
        val threadRecipient = storage.getRecipientForThread(threadId)
        val conversationVisibleInConfig = storage.conversationInConfig(
            if (message.groupPublicKey == null) threadRecipient?.address?.toString() else null,
            message.groupPublicKey,
            openGroupID,
            true
        )
        val canPerformChange = storage.canPerformConfigChange(
            if (threadRecipient?.address?.toString() == userPublicKey) ConfigDatabase.USER_PROFILE_VARIANT else ConfigDatabase.CONTACTS_VARIANT,
            userPublicKey,
            message.sentTimestamp!!
        )

        // If the thread is visible or the message was sent more recently than the last config message (minus
        // buffer period) then we should process the message, if not then the message is outdated
        return (!conversationVisibleInConfig && !canPerformChange)
    }

    private fun handleReadReceipt(message: ReadReceipt) {
        readReceiptManager.processReadReceipts(
            message.sender!!,
            message.timestamps!!,
            message.receivedTimestamp!!
        )
    }

    private fun handleCallMessage(message: CallMessage) {
        // TODO: refactor this out to persistence, just to help debug the flow and send/receive in synchronous testing
        WebRtcUtils.SIGNAL_QUEUE.trySend(message)
    }

    private fun handleTypingIndicator(message: TypingIndicator) {
        when (message.kind!!) {
            TypingIndicator.Kind.STARTED -> showTypingIndicatorIfNeeded(message.sender!!)
            TypingIndicator.Kind.STOPPED -> hideTypingIndicatorIfNeeded(message.sender!!)
        }
    }

    private fun showTypingIndicatorIfNeeded(senderPublicKey: String) {
        val address = Address.fromSerialized(senderPublicKey)
        val threadID = storage.getThreadId(address) ?: return
        typingIndicators.didReceiveTypingStartedMessage(threadID, address, 1)
    }

    private fun hideTypingIndicatorIfNeeded(senderPublicKey: String) {
        val address = Address.fromSerialized(senderPublicKey)
        val threadID = storage.getThreadId(address) ?: return
        typingIndicators.didReceiveTypingStoppedMessage(threadID, address, 1, false)
    }

    private fun cancelTypingIndicatorsIfNeeded(senderPublicKey: String) {
        val address = Address.fromSerialized(senderPublicKey)
        val threadID = storage.getThreadId(address) ?: return
        typingIndicators.didReceiveIncomingMessage(threadID, address, 1)
    }

    private fun handleExpirationTimerUpdate(message: ExpirationTimerUpdate) {
        messageExpirationManager.run {
            insertExpirationTimerMessage(message)
            onMessageReceived(message)
        }

        val isLegacyGroup = message.groupPublicKey != null && message.groupPublicKey?.startsWith(IdPrefix.GROUP.value) == false

        if (isNewConfigEnabled && !isLegacyGroup) return

        try {
            val threadId = Address.fromSerialized(message.groupPublicKey?.let(::doubleEncodeGroupID) ?: message.sender!!)
                .let(storage::getOrCreateThreadIdFor)

            storage.setExpirationConfiguration(
                ExpirationConfiguration(
                    threadId,
                    message.expiryMode,
                    message.sentTimestamp!!
                )
            )
        } catch (e: Exception) {
            Log.e("Loki", "Failed to update expiration configuration.")
        }
    }

    private fun handleDataExtractionNotification(message: DataExtractionNotification) {
        // We don't handle data extraction messages for groups (they shouldn't be sent, but just in case we filter them here too)
        if (message.groupPublicKey != null) return
        val senderPublicKey = message.sender!!

        val notification: DataExtractionNotificationInfoMessage = when(message.kind) {
            is DataExtractionNotification.Kind.Screenshot -> DataExtractionNotificationInfoMessage(DataExtractionNotificationInfoMessage.Kind.SCREENSHOT)
            is DataExtractionNotification.Kind.MediaSaved -> DataExtractionNotificationInfoMessage(DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED)
            else -> return
        }
        storage.insertDataExtractionNotificationMessage(senderPublicKey, notification, message.sentTimestamp!!)
    }


    fun handleUnsendRequest(message: UnsendRequest): MessageId? {
        val userPublicKey = storage.getUserPublicKey()
        val userAuth = storage.userAuth ?: return null
        val isLegacyGroupAdmin: Boolean = message.groupPublicKey?.let { key ->
            var admin = false
            val groupID = doubleEncodeGroupID(key)
            val group = storage.getGroup(groupID)
            if(group != null) {
                admin = group.admins.map { it.toString() }.contains(message.sender)
            }
            admin
        } ?: false

        // First we need to determine the validity of the UnsendRequest
        // It is valid if:
        val requestIsValid = message.sender == message.author || //  the sender is the author of the message
                message.author == userPublicKey || //  the sender is the current user
                isLegacyGroupAdmin // sender is an admin of legacy group

        if (!requestIsValid) { return null }

        val timestamp = message.timestamp ?: return null
        val author = message.author ?: return null
        val messageToDelete = storage.getMessageBy(timestamp, author) ?: return null
        val messageIdToDelete = messageToDelete.messageId
        val messageType = messageToDelete.individualRecipient?.getType()

        // send a /delete rquest for 1on1 messages
        if (messageType == MessageType.ONE_ON_ONE) {
            messageDataProvider.getServerHashForMessage(messageIdToDelete)?.let { serverHash ->
                GlobalScope.launch(Dispatchers.IO) { // using GlobalScope as we are slowly migrating to coroutines but we can't migrate everything at once
                    try {
                        SnodeAPI.deleteMessage(author, userAuth, listOf(serverHash))
                    } catch (e: Exception) {
                        Log.e("Loki", "Failed to delete message", e)
                    }
                }
            }
        }

        // the message is marked as deleted locally
        // except for 'note to self' where the message is completely deleted
        if (messageType == MessageType.NOTE_TO_SELF){
            messageDataProvider.deleteMessage(messageIdToDelete)
        } else {
            messageDataProvider.markMessageAsDeleted(
                messageIdToDelete,
                displayedMessage = context.getString(R.string.deleteMessageDeletedGlobally)
            )
        }

        // delete reactions
        storage.deleteReactions(messageToDelete.messageId)

        // update notification
        if (!messageToDelete.isOutgoing) {
            notificationManager.updateNotification(context)
        }

        return messageIdToDelete
    }

    private fun handleMessageRequestResponse(message: MessageRequestResponse) {
        storage.insertMessageRequestResponseFromContact(message)
    }

    fun handleVisibleMessage(
        message: VisibleMessage,
        proto: SignalServiceProtos.Content,
        context: VisibleMessageHandlerContext,
        runThreadUpdate: Boolean,
        runProfileUpdate: Boolean
    ): MessageId? {
        val userPublicKey = context.storage.getUserPublicKey()
        val messageSender: String? = message.sender

        // Do nothing if the message was outdated
        if (messageIsOutdated(message, context.threadId, context.openGroupID)) { return null }

        // Update profile if needed
        val recipient = Recipient.from(context.context, Address.fromSerialized(messageSender!!), false)
        if (runProfileUpdate) {
            val profile = message.profile
            val isUserBlindedSender = messageSender == context.userBlindedKey
            if (profile != null && userPublicKey != messageSender && !isUserBlindedSender) {
                val name = profile.displayName!!
                if (name.isNotEmpty() && name != recipient.rawName) {
                    context.profileManager.setName(context.context, recipient, name)
                }
                val newProfileKey = profile.profileKey

                val needsProfilePicture = !AvatarHelper.avatarFileExists(context.context, Address.fromSerialized(messageSender))
                val profileKeyValid = newProfileKey?.isNotEmpty() == true && (newProfileKey.size == 16 || newProfileKey.size == 32) && profile.profilePictureURL?.isNotEmpty() == true
                val profileKeyChanged = (recipient.profileKey == null || !MessageDigest.isEqual(recipient.profileKey, newProfileKey))

                if ((profileKeyValid && profileKeyChanged) || (profileKeyValid && needsProfilePicture)) {
                    context.profileManager.setProfilePicture(context.context, recipient, profile.profilePictureURL, newProfileKey)
                } else if (newProfileKey == null || newProfileKey.isEmpty() || profile.profilePictureURL.isNullOrEmpty()) {
                    context.profileManager.setProfilePicture(context.context, recipient, null, null)
                }
            }

            if (userPublicKey != messageSender && !isUserBlindedSender && message.blocksMessageRequests != recipient.blocksCommunityMessageRequests) {
                context.storage.setBlocksCommunityMessageRequests(recipient, message.blocksMessageRequests)
            }

            // update the disappearing / legacy banner for the sender
            val disappearingState = when {
                proto.dataMessage.expireTimer > 0 && !proto.hasExpirationType() -> Recipient.DisappearingState.LEGACY
                else -> Recipient.DisappearingState.UPDATED
            }
            if(disappearingState != recipient.disappearingState) {
                context.storage.updateDisappearingState(
                    messageSender,
                    context.threadId,
                    disappearingState
                )
            }
        }
        // Handle group invite response if new closed group
        if (context.threadRecipient?.isGroupV2Recipient == true) {
            GlobalScope.launch {
                try {
                    groupManagerV2
                        .handleInviteResponse(
                            AccountId(context.threadRecipient!!.address.toString()),
                            AccountId(messageSender),
                            approved = true
                        )
                } catch (e: Exception) {
                    Log.e("Loki", "Failed to handle invite response", e)
                }
            }
        }
        // Parse quote if needed
        var quoteModel: QuoteModel? = null
        var quoteMessageBody: String? = null
        if (message.quote != null && proto.dataMessage.hasQuote()) {
            val quote = proto.dataMessage.quote

            val author = if (quote.author == context.userBlindedKey) {
                Address.fromSerialized(userPublicKey!!)
            } else {
                Address.fromSerialized(quote.author)
            }

            val messageInfo = messageDataProvider.getMessageForQuote(quote.id, author)
            quoteMessageBody = messageInfo?.third
            quoteModel = if (messageInfo != null) {
                val attachments = if (messageInfo.second) messageDataProvider.getAttachmentsAndLinkPreviewFor(messageInfo.first) else ArrayList()
                QuoteModel(quote.id, author,null,false, attachments)
            } else {
                QuoteModel(quote.id, author,null, true, PointerAttachment.forPointers(proto.dataMessage.quote.attachmentsList))
            }
        }
        // Parse link preview if needed
        val linkPreviews: MutableList<LinkPreview?> = mutableListOf()
        if (message.linkPreview != null && proto.dataMessage.previewCount > 0) {
            for (preview in proto.dataMessage.previewList) {
                val thumbnail = PointerAttachment.forPointer(preview.image)
                val url = Optional.fromNullable(preview.url)
                val title = Optional.fromNullable(preview.title)
                val hasContent = !TextUtils.isEmpty(title.or("")) || thumbnail.isPresent
                if (hasContent) {
                    val linkPreview = LinkPreview(url.get(), title.or(""), thumbnail)
                    linkPreviews.add(linkPreview)
                } else {
                    Log.w("Loki", "Discarding an invalid link preview. hasContent: $hasContent")
                }
            }
        }
        // Parse attachments if needed
        val attachments = proto.dataMessage.attachmentsList.map(Attachment::fromProto).filter { it.isValid() }

        // Cancel any typing indicators if needed
        cancelTypingIndicatorsIfNeeded(message.sender!!)

        // Parse reaction if needed
        val threadIsGroup = context.threadRecipient?.isGroupOrCommunityRecipient == true
        message.reaction?.let { reaction ->
            if (reaction.react == true) {
                reaction.serverId = message.openGroupServerMessageID?.toString() ?: message.serverHash.orEmpty()
                reaction.dateSent = message.sentTimestamp ?: 0
                reaction.dateReceived = message.receivedTimestamp ?: 0
                context.storage.addReaction(
                    threadId = context.threadId,
                    reaction = reaction,
                    messageSender = messageSender,
                    notifyUnread = !threadIsGroup
                )
            } else {
                context.storage.removeReaction(
                    emoji = reaction.emoji!!,
                    messageTimestamp = reaction.timestamp!!,
                    threadId = context.threadId,
                    author = reaction.publicKey!!,
                    notifyUnread = threadIsGroup
                )
            }
        } ?: run {
            // A user is mentioned if their public key is in the body of a message or one of their messages
            // was quoted

            // Verify the incoming message length and truncate it if needed, before saving it to the db
            val maxChars = proStatusManager.getIncomingMessageMaxLength(message)
            val messageText = message.text?.take(maxChars) // truncate to max char limit for this message
            message.text = messageText
            message.hasMention = listOf(userPublicKey, context.userBlindedKey)
                .filterNotNull()
                .any { key ->
                    messageText?.contains("@$key") == true || key == (quoteModel?.author?.toString() ?: "")
                }

            // Persist the message
            message.threadID = context.threadId

            // clean up the message - For example we do not want any expiration data on messages for communities
            if(message.openGroupServerMessageID != null){
                message.expiryMode = ExpiryMode.NONE
            }

            val messageID = context.storage.persist(message, quoteModel, linkPreviews, message.groupPublicKey, context.openGroupID, attachments, runThreadUpdate) ?: return null
            // Parse & persist attachments
            // Start attachment downloads if needed
            if (messageID.mms && (context.threadRecipient?.autoDownloadAttachments == true || messageSender == userPublicKey)) {
                context.storage.getAttachmentsForMessage(messageID.id).iterator().forEach { attachment ->
                    attachment.attachmentId?.let { id ->
                        JobQueue.shared.add(attachmentDownloadJobFactory.create(
                            attachmentID = id.rowId,
                            mmsMessageId = messageID.id
                        ))
                    }
                }
            }
            message.openGroupServerMessageID?.let {
                context.storage.setOpenGroupServerMessageID(
                    messageID = messageID,
                    serverID = it,
                    threadID = context.threadId
                )
            }
            message.id = messageID
            context.messageExpirationManager.onMessageReceived(message)
            return messageID
        }
        return null
    }

    private fun MessageReceiver.handleGroupUpdated(message: GroupUpdated, closedGroup: AccountId?) {
        val inner = message.inner
        if (closedGroup == null &&
            !inner.hasInviteMessage() && !inner.hasPromoteMessage()) {
            throw NullPointerException("Message wasn't polled from a closed group!")
        }

        // Update profile if needed
        if (message.profile != null && !message.isSenderSelf) {
            val profile = message.profile
            val recipient = Recipient.from(context, Address.fromSerialized(message.sender!!), false)
            if (profile.displayName?.isNotEmpty() == true) {
                profileManager.setName(context, recipient, profile.displayName)
            }
            if (profile.profileKey?.isNotEmpty() == true && !profile.profilePictureURL.isNullOrEmpty()) {
                profileManager.setProfilePicture(context, recipient, profile.profilePictureURL, profile.profileKey)
            }
        }

        when {
            inner.hasInviteMessage() -> handleNewLibSessionClosedGroupMessage(message)
            inner.hasInviteResponse() -> handleInviteResponse(message, closedGroup!!)
            inner.hasPromoteMessage() -> handlePromotionMessage(message)
            inner.hasInfoChangeMessage() -> handleGroupInfoChange(message, closedGroup!!)
            inner.hasMemberChangeMessage() -> handleMemberChange(message, closedGroup!!)
            inner.hasMemberLeftMessage() -> handleMemberLeft(message, closedGroup!!)
            inner.hasMemberLeftNotificationMessage() -> handleMemberLeftNotification(message, closedGroup!!)
            inner.hasDeleteMemberContent() -> handleDeleteMemberContent(message, closedGroup!!)
        }
    }

    private fun handleDeleteMemberContent(message: GroupUpdated, closedGroup: AccountId) {
        val deleteMemberContent = message.inner.deleteMemberContent
        val adminSig = if (deleteMemberContent.hasAdminSignature()) deleteMemberContent.adminSignature.toByteArray()!! else byteArrayOf()

        val hasValidAdminSignature = adminSig.isNotEmpty() && runCatching {
            verifyAdminSignature(
                closedGroup,
                adminSig,
                buildDeleteMemberContentSignature(
                    memberIds = deleteMemberContent.memberSessionIdsList.asSequence().map(::AccountId).asIterable(),
                    messageHashes = deleteMemberContent.messageHashesList,
                    timestamp = message.sentTimestamp!!,
                )
            )
        }.isSuccess

        GlobalScope.launch {
            try {
                groupManagerV2.handleDeleteMemberContent(
                    groupId = closedGroup,
                    deleteMemberContent = deleteMemberContent,
                    timestamp = message.sentTimestamp!!,
                    sender = AccountId(message.sender!!),
                    senderIsVerifiedAdmin = hasValidAdminSignature
                )
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle delete member content", e)
            }
        }
    }

    private fun handleMemberChange(message: GroupUpdated, closedGroup: AccountId) {
        val memberChange = message.inner.memberChangeMessage
        val type = memberChange.type
        val timestamp = message.sentTimestamp!!
        verifyAdminSignature(closedGroup,
            memberChange.adminSignature.toByteArray(),
            buildMemberChangeSignature(type, timestamp)
        )
        storage.insertGroupInfoChange(message, closedGroup)
    }

    private fun handleMemberLeft(message: GroupUpdated, closedGroup: AccountId) {
        GlobalScope.launch(Dispatchers.Default) {
            try {
                groupManagerV2.handleMemberLeftMessage(
                    AccountId(message.sender!!), closedGroup
                )
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle member left message", e)
            }
        }
    }

    private fun handleMemberLeftNotification(message: GroupUpdated, closedGroup: AccountId) {
        storage.insertGroupInfoChange(message, closedGroup)
    }

    private fun handleGroupInfoChange(message: GroupUpdated, closedGroup: AccountId) {
        val inner = message.inner
        val infoChanged = inner.infoChangeMessage ?: return
        if (!infoChanged.hasAdminSignature()) return Log.e("GroupUpdated", "Info changed message doesn't contain admin signature")
        val adminSignature = infoChanged.adminSignature
        val type = infoChanged.type
        val timestamp = message.sentTimestamp!!
        verifyAdminSignature(closedGroup, adminSignature.toByteArray(), buildInfoChangeSignature(type, timestamp))

        groupManagerV2.handleGroupInfoChange(message, closedGroup)
    }

    private fun handlePromotionMessage(message: GroupUpdated) {
        val promotion = message.inner.promoteMessage
        val seed = promotion.groupIdentitySeed.toByteArray()
        val sender = message.sender!!
        val adminId = AccountId(sender)
        GlobalScope.launch {
            try {
                groupManagerV2
                    .handlePromotion(
                        groupId = AccountId(IdPrefix.GROUP, ED25519.generate(seed).pubKey.data),
                        groupName = promotion.name,
                        adminKeySeed = seed,
                        promoter = adminId,
                        promoterName = message.profile?.displayName,
                        promoteMessageHash = message.serverHash!!,
                        promoteMessageTimestamp = message.sentTimestamp!!,
                    )
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle promotion message", e)
            }
        }
    }

    private fun handleInviteResponse(message: GroupUpdated, closedGroup: AccountId) {
        val sender = message.sender!!
        // val profile = message // maybe we do need data to be the inner so we can access profile
        val storage = storage
        val approved = message.inner.inviteResponse.isApproved
        GlobalScope.launch {
            try {
                groupManagerV2.handleInviteResponse(closedGroup, AccountId(sender), approved)
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle invite response", e)
            }
        }
    }

    private fun handleNewLibSessionClosedGroupMessage(message: GroupUpdated) {
        val storage = storage
        val ourUserId = storage.getUserPublicKey()!!
        val invite = message.inner.inviteMessage
        val groupId = AccountId(invite.groupSessionId)
        verifyAdminSignature(
            groupSessionId = groupId,
            signatureData = invite.adminSignature.toByteArray(),
            messageToValidate = buildGroupInviteSignature(AccountId(ourUserId), message.sentTimestamp!!)
        )

        val sender = message.sender!!
        val adminId = AccountId(sender)
        GlobalScope.launch {
            try {
                groupManagerV2
                    .handleInvitation(
                        groupId = groupId,
                        groupName = invite.name,
                        authData = invite.memberAuthData.toByteArray(),
                        inviter = adminId,
                        inviterName = message.profile?.displayName,
                        inviteMessageHash = message.serverHash!!,
                        inviteMessageTimestamp = message.sentTimestamp!!,
                    )
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle invite message", e)
            }
        }
    }


    /**
     * Does nothing on successful signature verification, throws otherwise.
     * Assumes the signer is using the ed25519 group key signing key
     * @param groupSessionId the AccountId of the group to check the signature against
     * @param signatureData the byte array supplied to us through a protobuf message from the admin
     * @param messageToValidate the expected values used for this signature generation, often something like `INVITE||{inviteeSessionId}||{timestamp}`
     * @throws SignatureException if signature cannot be verified with given parameters
     */
    private fun verifyAdminSignature(groupSessionId: AccountId, signatureData: ByteArray, messageToValidate: ByteArray) {
        val groupPubKey = groupSessionId.pubKeyBytes
        if (!ED25519.verify(signature = signatureData, ed25519PublicKey = groupPubKey, message = messageToValidate)) {
            throw SignatureException("Verification failed for signature data")
        }
    }

    private fun isValidGroupUpdate(group: GroupRecord, sentTimestamp: Long, senderPublicKey: String): Boolean {
        val oldMembers = group.members.map { it.toString() }
        // Check that the message isn't from before the group was created
        if (group.formationTimestamp > sentTimestamp) {
            Log.d("Loki", "Ignoring closed group update from before thread was created.")
            return false
        }
        // Check that the sender is a member of the group (before the update)
        if (senderPublicKey !in oldMembers) {
            Log.d("Loki", "Ignoring closed group info message from non-member.")
            return false
        }
        return true
    }

    fun disableLocalGroupAndUnsubscribe(groupPublicKey: String, groupID: String, userPublicKey: String, delete: Boolean) {
        val storage = storage
        storage.removeClosedGroupPublicKey(groupPublicKey)
        // Remove the key pairs
        storage.removeAllClosedGroupEncryptionKeyPairs(groupPublicKey)
        // Mark the group as inactive
        storage.setActive(groupID, false)
        storage.removeMember(groupID, Address.fromSerialized(userPublicKey))
        // Notify the PN server
        PushRegistryV1.unsubscribeGroup(groupPublicKey, publicKey = userPublicKey)

        if (delete) {
            storage.getThreadId(Address.fromSerialized(groupID))?.let { threadId ->
                storage.cancelPendingMessageSendJobs(threadId)
                storage.deleteConversation(threadId)
            }
        }
    }

}




// region Control Messages


//endregion

private fun SignalServiceProtos.Content.ExpirationType.expiryMode(durationSeconds: Long) = takeIf { durationSeconds > 0 }?.let {
    when (it) {
        SignalServiceProtos.Content.ExpirationType.DELETE_AFTER_READ -> ExpiryMode.AfterRead(durationSeconds)
        SignalServiceProtos.Content.ExpirationType.DELETE_AFTER_SEND, SignalServiceProtos.Content.ExpirationType.UNKNOWN -> ExpiryMode.AfterSend(durationSeconds)
        else -> ExpiryMode.NONE
    }
} ?: ExpiryMode.NONE


class VisibleMessageHandlerContext @AssistedInject constructor(
    @param:ApplicationContext val context: Context,
    @Assisted val threadId: Long,
    @Assisted val openGroupID: String?,
    val storage: StorageProtocol,
    val profileManager: SSKEnvironment.ProfileManagerProtocol,
    val groupManagerV2: GroupManagerV2,
    val messageExpirationManager: SSKEnvironment.MessageExpirationManagerProtocol,
    val messageDataProvider: MessageDataProvider,
) {
    val openGroup: OpenGroup? by lazy {
        openGroupID?.let { storage.getOpenGroup(threadId) }
    }

    val userBlindedKey: String? by lazy {
        openGroup?.let {
            val blindedKey = BlindKeyAPI.blind15KeyPairOrNull(
                ed25519SecretKey = storage.getUserED25519KeyPair()!!.secretKey.data,
                serverPubKey = Hex.fromStringCondensed(it.publicKey),
            ) ?: return@let null

            AccountId(
                IdPrefix.BLINDED, blindedKey.pubKey.data
            ).hexString
        }
    }

    val userPublicKey: String? by lazy {
        storage.getUserPublicKey()
    }

    val threadRecipient: Recipient? by lazy {
        storage.getRecipientForThread(threadId)
    }


    @AssistedFactory
    interface Factory {
        fun create(
            threadId: Long,
            openGroupID: String?
        ): VisibleMessageHandlerContext
    }
}


/**
 * Constructs reaction records for a given open group message.
 *
 * If the open group message exists in our database, we'll construct a list of reaction records
 * that is specified in the [reactions].
 *
 * Note that this function does not know or check if the local message has any reactions,
 * you'll be responsible for that. In simpler words, [out] only contains reactions that are given
 * to this function, it will not include any existing reactions in the database.
 *
 * @param openGroupMessageServerID The server ID of this message
 * @param context The context containing necessary data for processing reactions
 * @param reactions A map of emoji to [OpenGroupApi.Reaction] objects, representing the reactions for the message
 * @param out A mutable map that will be populated with [ReactionRecord]s, keyed by [MessageId]
 */
fun constructReactionRecords(
    openGroupMessageServerID: Long,
    context: VisibleMessageHandlerContext,
    reactions: Map<String, OpenGroupApi.Reaction>?,
    out: MutableMap<MessageId, MutableList<ReactionRecord>>
) {
    if (reactions.isNullOrEmpty()) return
    val messageId = context.messageDataProvider.getMessageID(openGroupMessageServerID, context.threadId) ?: return

    val outList = out.getOrPut(messageId) { arrayListOf() }

    for ((emoji, reaction) in reactions) {
        val pendingUserReaction = OpenGroupApi.pendingReactions
            .filter { it.server == context.openGroup?.server && it.room == context.openGroup?.room && it.messageId == openGroupMessageServerID && it.add }
            .sortedByDescending { it.seqNo }
            .any { it.emoji == emoji }
        val shouldAddUserReaction = pendingUserReaction || reaction.you || reaction.reactors.contains(context.userPublicKey)
        val reactorIds = reaction.reactors.filter { it != context.userBlindedKey && it != context.userPublicKey }
        val count = if (reaction.you) reaction.count - 1 else reaction.count
        // Add the first reaction (with the count)
        reactorIds.firstOrNull()?.let { reactor ->
            outList += ReactionRecord(
                messageId = messageId,
                author = reactor,
                emoji = emoji,
                serverId = openGroupMessageServerID.toString(),
                count = count,
                sortId = reaction.index,
            )
        }

        // Add all other reactions
        val maxAllowed = if (shouldAddUserReaction) 4 else 5
        val lastIndex = min(maxAllowed, reactorIds.size)
        reactorIds.slice(1 until lastIndex).map { reactor ->
            outList += ReactionRecord(
                messageId = messageId,
                author = reactor,
                emoji = emoji,
                serverId = openGroupMessageServerID.toString(),
                count = 0,  // Only want this on the first reaction
                sortId = reaction.index,
            )
        }

        // Add the current user reaction (if applicable and not already included)
        if (shouldAddUserReaction) {
            outList += ReactionRecord(
                messageId = messageId,
                author = context.userPublicKey!!,
                emoji = emoji,
                serverId = openGroupMessageServerID.toString(),
                count = 1,
                sortId = reaction.index,
            )
        }
    }
}

//endregion

// region Closed Groups



// endregion

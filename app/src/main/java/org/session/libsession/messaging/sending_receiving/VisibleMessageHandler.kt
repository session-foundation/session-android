package org.session.libsession.messaging.sending_receiving

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.protocol.DecodedPro
import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.Util
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.ProfileUpdateHandler
import org.session.libsession.messaging.messages.visible.Attachment
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.isGroupOrCommunity
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.updateContact
import org.session.libsession.utilities.upsertContact
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.Log
import org.session.protos.SessionProtos
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.pro.ProStatusManager
import javax.inject.Inject
import javax.inject.Provider

class VisibleMessageHandler @Inject constructor(
    private val storage: Storage,
    private val messageRequestResponseHandler: MessageRequestResponseHandler,
    @param:ManagerScope private val scope: CoroutineScope,
    private val groupManagerV2: GroupManagerV2,
    private val messageDataProvider: MessageDataProvider,
    private val proStatusManager: ProStatusManager,
    private val configFactory: ConfigFactory,
    private val profileUpdateHandler: Provider<ProfileUpdateHandler>,
    private val attachmentDownloadJobFactory: AttachmentDownloadJob.Factory,
    private val messageExpirationManager: SSKEnvironment.MessageExpirationManagerProtocol,
    private val typingIndicators: SSKEnvironment.TypingIndicatorsProtocol,
    private val clock: SnodeClock,
    private val jobQueue: Provider<JobQueue>,
){
    fun handleVisibleMessage(
        ctx: ReceivedMessageProcessor.MessageProcessingContext,
        message: VisibleMessage,
        pro: DecodedPro?,
        threadId: Long,
        threadAddress: Address.Conversable,
        proto: SessionProtos.Content,
        runThreadUpdate: Boolean,
        runProfileUpdate: Boolean,
    ): MessageId? {
        val senderAddress = message.sender!!.toAddress()

        messageRequestResponseHandler.handleVisibleMessage(ctx, message)

        // Handle group invite response if new closed group
        if (threadAddress is Address.Group && senderAddress is Address.Standard) {
            scope.launch {
                try {
                    groupManagerV2
                        .handleInviteResponse(
                            threadAddress.accountId,
                            senderAddress.accountId,
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

            var author = quote.author.toAddress()

            if (author is Address.WithAccountId && author.accountId in ctx.getCurrentUserBlindedIDsByThread(threadAddress)) {
                author = Address.Standard(ctx.currentUserId)
            }

            val messageInfo = messageDataProvider.getMessageForQuote(threadId, quote.id, author)
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
                val url = preview.url
                val title = preview.title
                val hasContent = !title.isNullOrEmpty() || thumbnail != null
                if (hasContent) {
                    val linkPreview = LinkPreview(
                        url = url.orEmpty(),
                        title = title.orEmpty(),
                        thumbnail = thumbnail
                    )
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
        val threadIsGroup = threadAddress.isGroupOrCommunity
        message.reaction?.let { reaction ->
            if (reaction.react == true) {
                reaction.serverId = message.openGroupServerMessageID?.toString() ?: message.serverHash.orEmpty()
                reaction.dateSent = message.sentTimestamp ?: 0
                reaction.dateReceived = message.receivedTimestamp ?: 0
                storage.addReaction(
                    threadId = threadId,
                    reaction = reaction,
                    messageSender = senderAddress.address,
                    notifyUnread = !threadIsGroup
                )
            } else {
                storage.removeReaction(
                    emoji = reaction.emoji!!,
                    messageTimestamp = reaction.timestamp!!,
                    threadId = threadId,
                    author = senderAddress.address,
                    notifyUnread = threadIsGroup
                )
            }
        } ?: run {
            // A user is mentioned if their public key is in the body of a message or one of their messages
            // was quoted

            // Verify the incoming message length and truncate it if needed, before saving it to the db
            val maxChars = proStatusManager.getIncomingMessageMaxLength(message)
            val messageText = message.text?.let { Util.truncateCodepoints(it, maxChars) } // truncate to max char limit for this message
            message.text = messageText
            message.hasMention = (sequenceOf(ctx.currentUserPublicKey) + ctx.getCurrentUserBlindedIDsByThread(threadAddress).asSequence())
                .any { key ->
                    messageText?.contains("@$key") == true || key == (quoteModel?.author?.toString() ?: "")
                }

            // Persist the message
            message.threadID = threadId

            // clean up the message - For example we do not want any expiration data on messages for communities
            if(message.openGroupServerMessageID != null){
                message.expiryMode = ExpiryMode.NONE
            }

            val threadRecipient = ctx.getThreadRecipient(threadAddress)
            val messageID = storage.persist(
                threadRecipient = threadRecipient,
                message = message,
                quotes = quoteModel,
                linkPreview = linkPreviews,
                attachments = attachments,
                runThreadUpdate = runThreadUpdate
            ) ?: return null

            // If we have previously "hidden" the sender, we should flip the flag back to visible,
            // and this should only be done only for 1:1 messages
            if (senderAddress is Address.Standard &&
                senderAddress.address != ctx.currentUserPublicKey &&
                threadAddress is Address.Standard) {
                val existingContact =
                    configFactory.withUserConfigs { it.contacts.get(senderAddress.accountId.hexString) }

                if (existingContact != null && existingContact.priority == PRIORITY_HIDDEN) {
                    Log.d(TAG, "Flipping thread for ${senderAddress.debugString} to visible")
                    configFactory.withMutableUserConfigs { configs ->
                        configs.contacts.updateContact(senderAddress) {
                            priority = PRIORITY_VISIBLE
                        }
                    }
                } else if (existingContact == null || !existingContact.approvedMe) {
                    // If we don't have the contact, create a new one with approvedMe = true
                    Log.d(TAG, "Creating new contact for ${senderAddress.debugString} with approvedMe = true")
                    configFactory.withMutableUserConfigs { configs ->
                        configs.contacts.upsertContact(senderAddress) {
                            approvedMe = true
                        }
                    }
                }
            }

            // Update profile if needed:
            // - must be done after the message is persisted)
            // - must be done after neccessary contact is created
            if (runProfileUpdate && senderAddress is Address.WithAccountId) {
                val updates = ProfileUpdateHandler.Updates.create(
                    content = proto,
                    nowMills = clock.currentTimeMillis(),
                    pro = pro
                )

                if (updates != null) {
                    profileUpdateHandler.get().handleProfileUpdate(
                        senderId = senderAddress.accountId,
                        updates = updates,
                        fromCommunity = (threadRecipient.data as? RecipientData.Community)?.let { data ->
                            BaseCommunityInfo(baseUrl = data.serverUrl, room = data.room, pubKeyHex = data.serverPubKey)
                        },
                    )
                }
            }

            // Parse & persist attachments
            // Start attachment downloads if needed
            if (messageID.mms && (threadRecipient.autoDownloadAttachments == true || senderAddress.address == ctx.currentUserPublicKey)) {
                storage.getAttachmentsForMessage(messageID.id).iterator().forEach { attachment ->
                    attachment.attachmentId?.let { id ->
                        jobQueue.get().add(
                            attachmentDownloadJobFactory.create(
                            attachmentID = id.rowId,
                            mmsMessageId = messageID.id
                        ))
                    }
                }
            }
            message.openGroupServerMessageID?.let {
                storage.setOpenGroupServerMessageID(
                    messageID = messageID,
                    serverID = it,
                    threadID = threadId
                )
            }
            message.id = messageID
            messageExpirationManager.onMessageReceived(message)
            return messageID
        }
        return null
    }

    private fun cancelTypingIndicatorsIfNeeded(senderPublicKey: String) {
        val address = Address.fromSerialized(senderPublicKey)
        val threadID = storage.getThreadId(address) ?: return
        typingIndicators.didReceiveIncomingMessage(threadID, address, 1)
    }

    companion object {
        private const val TAG = "VisibleMessageHandler"
    }
}
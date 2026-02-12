package org.thoughtcrime.securesms.conversation.v2.utilities

import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.visible.LinkPreview
import org.session.libsession.messaging.messages.visible.OpenGroupInvitation
import org.session.libsession.messaging.messages.visible.Quote
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.isGroupOrCommunity
import org.session.libsession.utilities.toGroupString
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import javax.inject.Inject

class ResendMessageUtilities @Inject constructor(
    private val messageSender: MessageSender,
    private val storage: StorageProtocol,
) {

    suspend fun resend(accountId: String?, messageRecord: MessageRecord, userBlindedKey: String?, isResync: Boolean = false) {
        val recipient = messageRecord.recipient.address
        val message = VisibleMessage()
        message.id = messageRecord.messageId
        if (messageRecord.isOpenGroupInvitation) {
            val openGroupInvitation = OpenGroupInvitation()
            UpdateMessageData.fromJSON(messageRecord.body)?.let { updateMessageData ->
                val kind = updateMessageData.kind
                if (kind is UpdateMessageData.Kind.OpenGroupInvitation) {
                    openGroupInvitation.name = kind.groupName
                    openGroupInvitation.url = kind.groupUrl
                }
            }
            message.openGroupInvitation = openGroupInvitation
        } else {
            message.text = messageRecord.body
            message.proFeatures = messageRecord.proFeatures
        }
        message.sentTimestamp = messageRecord.timestamp
        if (recipient.isGroupOrCommunity) {
            message.groupPublicKey = recipient.toGroupString()
        } else {
            message.recipient = messageRecord.recipient.address.toString()
        }
        message.threadID = messageRecord.threadId
        if (messageRecord.isMms && messageRecord is MmsMessageRecord) {
            messageRecord.linkPreviews.firstOrNull()?.let { message.linkPreview = LinkPreview.from(it) }
            messageRecord.quote?.quoteModel?.let {
                message.quote = Quote.from(it)?.apply {
                    if (userBlindedKey != null && publicKey == accountId) {
                        publicKey = userBlindedKey
                    }
                }
            }
            message.addSignalAttachments(messageRecord.slideDeck.asAttachments())
        }
        val sentTimestamp = message.sentTimestamp
        val sender = storage.getUserPublicKey()
        if (sentTimestamp != null && sender != null) {
            if (isResync) {
                storage.markAsResyncing(messageRecord.messageId)
                messageSender.sendNonDurably(message, Destination.from(recipient), isSyncMessage = true)
            } else {
                storage.markAsSending(messageRecord.messageId)
                messageSender.send(message, recipient)
            }
        }
    }
}
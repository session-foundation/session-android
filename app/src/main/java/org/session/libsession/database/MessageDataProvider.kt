package org.session.libsession.database

import org.session.libsession.messaging.messages.MarkAsDeletedMessage
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.messaging.sending_receiving.attachments.SessionServiceAttachmentPointer
import org.session.libsession.messaging.sending_receiving.attachments.SessionServiceAttachmentStream
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.UploadResult
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.messages.SignalServiceAttachmentStream
import org.thoughtcrime.securesms.database.model.MessageId
import java.io.InputStream

interface MessageDataProvider {

    fun getMessageID(serverId: Long, threadId: Long): MessageId?
    fun getMessageIDs(serverIDs: List<Long>, threadID: Long): Pair<List<Long>, List<Long>>
    fun getUserMessageHashes(threadId: Long, userPubKey: String): List<String>
    fun deleteMessage(messageId: MessageId)
    fun deleteMessages(messageIDs: List<Long>, threadId: Long, isSms: Boolean)
    fun markMessageAsDeleted(messageId: MessageId, displayedMessage: String)
    fun markMessagesAsDeleted(messages: List<MarkAsDeletedMessage>, displayedMessage: String)
    fun markMessagesAsDeleted(threadId: Long, serverHashes: List<String>, displayedMessage: String)
    fun markUserMessagesAsDeleted(threadId: Long, until: Long, sender: String, displayedMessage: String)
    fun getServerHashForMessage(messageID: MessageId): String?
    fun getDatabaseAttachment(attachmentId: Long): DatabaseAttachment?
    fun getAttachmentStream(attachmentId: Long): SessionServiceAttachmentStream?
    fun getAttachmentPointer(attachmentId: Long): SessionServiceAttachmentPointer?
    fun getSignalAttachmentStream(attachmentId: Long): SignalServiceAttachmentStream?
    fun getScaledSignalAttachmentStream(attachmentId: Long): SignalServiceAttachmentStream?
    fun getSignalAttachmentPointer(attachmentId: Long): SignalServiceAttachmentPointer?
    fun setAttachmentState(attachmentState: AttachmentState, attachmentId: AttachmentId, messageID: Long)
    fun insertAttachment(messageId: Long, attachmentId: AttachmentId, stream : InputStream)
    fun updateAudioAttachmentDuration(attachmentId: AttachmentId, durationMs: Long, threadId: Long)
    fun isOutgoingMessage(id: MessageId): Boolean
    fun isDeletedMessage(id: MessageId): Boolean
    fun handleSuccessfulAttachmentUpload(attachmentId: Long, attachmentStream: SignalServiceAttachmentStream, attachmentKey: ByteArray, uploadResult: UploadResult)
    fun handleFailedAttachmentUpload(attachmentId: Long)
    fun getMessageForQuote(timestamp: Long, author: Address): Triple<Long, Boolean, String>?
    fun getAttachmentsAndLinkPreviewFor(mmsId: Long): List<Attachment>
    fun getMessageBodyFor(timestamp: Long, author: String): String
    fun getAttachmentIDsFor(mmsMessageId: Long): List<Long>
    fun getLinkPreviewAttachmentIDFor(mmsMessageId: Long): Long?
    fun getIndividualRecipientForMms(mmsId: Long): Recipient?
}
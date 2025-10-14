package org.thoughtcrime.securesms.attachments

import android.content.Context
import android.text.TextUtils
import androidx.compose.ui.unit.IntSize
import coil3.size.Size
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.MarkAsDeletedMessage
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachmentAudioExtras
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment
import org.session.libsession.messaging.sending_receiving.attachments.SessionServiceAttachmentPointer
import org.session.libsession.messaging.sending_receiving.attachments.SessionServiceAttachmentStream
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.UploadResult
import org.session.libsession.utilities.Util
import org.session.libsignal.messages.SignalServiceAttachment
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.messages.SignalServiceAttachmentStream
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.Database
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MessagingDatabase
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.MediaStream
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class DatabaseAttachmentProvider @Inject constructor(
    @ApplicationContext context: Context,
    helper: Provider<SQLCipherOpenHelper>,
    private val attachmentDatabase: AttachmentDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: MmsDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val lokiMessageDatabase: LokiMessageDatabase,
    private val attachmentProcessor: AttachmentProcessor,
) : Database(context, helper), MessageDataProvider {

    override fun getAttachmentStream(attachmentId: Long): SessionServiceAttachmentStream? {
        val databaseAttachment = attachmentDatabase.getAttachment(AttachmentId(attachmentId, 0)) ?: return null
        return databaseAttachment.toAttachmentStream(context)
    }

    override fun getAttachmentPointer(attachmentId: Long): SessionServiceAttachmentPointer? {
        val databaseAttachment = attachmentDatabase.getAttachment(AttachmentId(attachmentId, 0)) ?: return null
        return databaseAttachment.toAttachmentPointer()
    }

    override fun getSignalAttachmentStream(attachmentId: Long): SignalServiceAttachmentStream? {
        val databaseAttachment = attachmentDatabase.getAttachment(AttachmentId(attachmentId, 0)) ?: return null
        return databaseAttachment.toSignalAttachmentStream(context)
    }

    override suspend fun getScaledSignalAttachmentStream(attachmentId: Long): SignalServiceAttachmentStream? {
        val id = AttachmentId(attachmentId, 0)
        val databaseAttachment = attachmentDatabase.getAttachment(id) ?: return null
        val mediaConstraints = MediaConstraints.getPushMediaConstraints()
        val scaledAttachment = processAttachment(attachmentDatabase, mediaConstraints, databaseAttachment) ?: return null
        return getAttachmentFor(scaledAttachment)
    }

    override fun getSignalAttachmentPointer(attachmentId: Long): SignalServiceAttachmentPointer? {
        val databaseAttachment = attachmentDatabase.getAttachment(AttachmentId(attachmentId, 0)) ?: return null
        return databaseAttachment.toSignalAttachmentPointer()
    }

    override fun setAttachmentState(attachmentState: AttachmentState, attachmentId: AttachmentId, messageID: Long) {
        attachmentDatabase.setTransferState(attachmentId, attachmentState.value)
    }

    override fun getMessageForQuote(threadId: Long, timestamp: Long, author: Address): Triple<Long, Boolean, String>? {
        val message = mmsSmsDatabase.getMessageFor(threadId, timestamp, author)
        return if (message != null) Triple(message.id, message.isMms, message.body) else null
    }

    override fun getAttachmentsAndLinkPreviewFor(mmsId: Long): List<Attachment> {
        return attachmentDatabase.getAttachmentsForMessage(mmsId)
    }

    override fun getAttachmentIDsFor(mmsMessageId: Long): List<Long> {
        return attachmentDatabase
            .getAttachmentsForMessage(mmsMessageId).mapNotNull {
            if (it.isQuote) return@mapNotNull null
            it.attachmentId.rowId
        }
    }

    override fun getLinkPreviewAttachmentIDFor(mmsMessageId: Long): Long? {
        val message = mmsDatabase.getOutgoingMessage(mmsMessageId)
        return message.linkPreviews.firstOrNull()?.attachmentId?.rowId
    }

    override fun insertAttachment(messageId: Long, attachmentId: AttachmentId, stream: InputStream) {
        attachmentDatabase.insertAttachmentsForPlaceholder(messageId, attachmentId, stream)
    }

    override fun updateAudioAttachmentDuration(
        attachmentId: AttachmentId,
        durationMs: Long,
        threadId: Long
    ) {
        attachmentDatabase.setAttachmentAudioExtras(
            DatabaseAttachmentAudioExtras(
                attachmentId = attachmentId,
                visualSamples = byteArrayOf(),
                durationMs = durationMs
            )
        )
    }

    override fun isOutgoingMessage(id: MessageId): Boolean {
        return if (id.mms) {
            mmsDatabase.isOutgoingMessage(id.id)
        } else {
            smsDatabase.isOutgoingMessage(id.id)
        }
    }

    override fun isDeletedMessage(id: MessageId): Boolean {
        return if (id.mms) {
            mmsDatabase.isDeletedMessage(id.id)
        } else {
            smsDatabase.isDeletedMessage(id.id)
        }
    }

    override fun handleSuccessfulAttachmentUpload(attachmentId: Long, attachmentStream: SignalServiceAttachmentStream, attachmentKey: ByteArray, uploadResult: UploadResult) {
        val databaseAttachment = getDatabaseAttachment(attachmentId) ?: return
        val attachmentPointer = SignalServiceAttachmentPointer(
            // The ID will be non-numeric in the future so we will do our best to convert it to a long,
            // as some old clients still use this value (we should use the url instead).
            uploadResult.id.toLongOrNull() ?: 0L,
            attachmentStream.contentType,
            attachmentKey,
            Optional.of(Util.toIntExact(attachmentStream.length)),
            attachmentStream.preview,
            attachmentStream.width, attachmentStream.height,
            Optional.fromNullable(uploadResult.digest),
            attachmentStream.filename,
            attachmentStream.voiceNote,
            attachmentStream.caption,
            uploadResult.url);
        val attachment = PointerAttachment.forPointer(Optional.of(attachmentPointer), databaseAttachment.fastPreflightId).get()
        attachmentDatabase.updateAttachmentAfterUploadSucceeded(databaseAttachment.attachmentId, attachment)
    }

    override fun handleFailedAttachmentUpload(attachmentId: Long) {
        val databaseAttachment = getDatabaseAttachment(attachmentId) ?: return
        attachmentDatabase.handleFailedAttachmentUpload(databaseAttachment.attachmentId)
    }

    override fun getMessageID(serverId: Long, threadId: Long): MessageId? {
        return lokiMessageDatabase.getMessageID(serverId, threadId)
    }

    override fun getMessageIDs(serverIds: List<Long>, threadId: Long): Pair<List<Long>, List<Long>> {
        return lokiMessageDatabase.getMessageIDs(serverIds, threadId)
    }

    override fun getUserMessageHashes(threadId: Long, userPubKey: String): List<String> {
        val messages = mmsSmsDatabase.getUserMessages(threadId, userPubKey)
        return messages.mapNotNull {
            lokiMessageDatabase.getMessageServerHash(it.messageId)
        }
    }

    override fun deleteMessage(messageId: MessageId) {
        if (messageId.mms) {
            mmsDatabase.deleteMessage(messageId.id)
        } else {
            smsDatabase.deleteMessage(messageId.id)
        }

        lokiMessageDatabase.deleteMessage(messageId)
        lokiMessageDatabase.deleteMessageServerHash(messageId)
    }

    override fun deleteMessages(messageIDs: List<Long>, isSms: Boolean) {
        val messagingDatabase: MessagingDatabase = if (isSms)  smsDatabase
                                                   else mmsDatabase

        messagingDatabase.deleteMessages(messageIDs)
        lokiMessageDatabase.deleteMessages(messageIDs, isSms = isSms)
        lokiMessageDatabase.deleteMessageServerHashes(messageIDs, mms = !isSms)
    }

    override fun markMessageAsDeleted(messageId: MessageId, displayedMessage: String) {
        val message = mmsSmsDatabase.getMessageById(messageId) ?: return Log.w("", "Failed to find message to mark as deleted")

        markMessagesAsDeleted(
            messages = listOf(MarkAsDeletedMessage(
                messageId = message.messageId,
                isOutgoing = message.isOutgoing
            )),
            displayedMessage = displayedMessage
        )
    }

    override fun markMessagesAsDeleted(
        messages: List<MarkAsDeletedMessage>,
        displayedMessage: String
    ) {
        messages.forEach { message ->
            if (message.messageId.mms) {
                mmsDatabase.markAsDeleted(message.messageId.id, message.isOutgoing, displayedMessage)
            } else {
                smsDatabase.markAsDeleted(message.messageId.id, message.isOutgoing, displayedMessage)
            }
        }
    }

    override fun markMessagesAsDeleted(
        threadId: Long,
        serverHashes: List<String>,
        displayedMessage: String
    ) {
        val markAsDeleteMessages = lokiMessageDatabase
            .getSendersForHashes(threadId, serverHashes.toSet())
            .map { MarkAsDeletedMessage(messageId = it.messageId, isOutgoing = it.isOutgoing) }

        markMessagesAsDeleted(markAsDeleteMessages, displayedMessage)
    }

    override fun markUserMessagesAsDeleted(
        threadId: Long,
        until: Long,
        sender: String,
        displayedMessage: String
    ) {
        val toDelete = mmsSmsDatabase.getUserMessages(threadId, sender)
            .asSequence()
            .filter { it.timestamp <= until }
            .map { record ->
                MarkAsDeletedMessage(messageId = record.messageId, isOutgoing = record.isOutgoing)
            }
            .toList()

        markMessagesAsDeleted(toDelete, displayedMessage)
    }

    override fun getServerHashForMessage(messageID: MessageId): String? =
        lokiMessageDatabase.getMessageServerHash(messageID)

    override fun getDatabaseAttachment(attachmentId: Long): DatabaseAttachment? =
        attachmentDatabase
            .getAttachment(AttachmentId(attachmentId, 0))

    private suspend fun processAttachment(
        attachmentDatabase: AttachmentDatabase,
        constraints: MediaConstraints,
        attachment: Attachment,
    ): Attachment? {
        return try {
            val result = attachmentProcessor.process(
                mimeType = attachment.contentType,
                data = { PartAuthority.getAttachmentStream(context, attachment.dataUri!!) },
                maxImageResolution = IntSize(constraints.getImageMaxWidth(context), constraints.getImageMaxHeight(context)),
                compressImage = false,
            ) ?: return null

            attachmentDatabase.updateAttachmentData(
                attachment,
                MediaStream(ByteArrayInputStream(result.data), result.mimeType, result.imageSize.width, result.imageSize.height)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing attachment", e)
            return null
        }
    }

    private fun getAttachmentFor(attachment: Attachment): SignalServiceAttachmentStream? {
        try {
            if (attachment.dataUri == null || attachment.size == 0L) throw IOException("Assertion failed, outgoing attachment has no data!")
            val `is` = PartAuthority.getAttachmentStream(context, attachment.dataUri!!)
            return SignalServiceAttachment.newStreamBuilder()
                    .withStream(`is`)
                    .withContentType(attachment.contentType)
                    .withLength(attachment.size)
                    .withFileName(attachment.filename)
                    .withVoiceNote(attachment.isVoiceNote)
                    .withWidth(attachment.width)
                    .withHeight(attachment.height)
                    .withCaption(attachment.caption)
                    .build()
        } catch (ioe: IOException) {
            Log.w("Loki", "Couldn't open attachment", ioe)
        }
        return null
    }


}

private const val TAG = "DatabaseAttachmentProvider"

fun DatabaseAttachment.toAttachmentPointer(): SessionServiceAttachmentPointer {
    return SessionServiceAttachmentPointer(attachmentId.rowId, contentType, key?.toByteArray(), Optional.fromNullable(size.toInt()), Optional.absent(), width, height, Optional.fromNullable(digest), filename, isVoiceNote, Optional.fromNullable(caption), url)
}

fun DatabaseAttachment.toAttachmentStream(context: Context): SessionServiceAttachmentStream {
    val stream = PartAuthority.getAttachmentStream(context, this.dataUri!!)

    var attachmentStream = SessionServiceAttachmentStream(stream, this.contentType, this.size, this.filename, this.isVoiceNote, Optional.absent(), this.width, this.height, Optional.fromNullable(this.caption))
    attachmentStream.attachmentId = this.attachmentId.rowId
    attachmentStream.isAudio = MediaUtil.isAudio(this)
    attachmentStream.isGif = MediaUtil.isGif(this)
    attachmentStream.isVideo = MediaUtil.isVideo(this)
    attachmentStream.isImage = MediaUtil.isImage(this)

    attachmentStream.key = ByteString.copyFrom(this.key?.toByteArray())
    attachmentStream.digest = Optional.fromNullable(this.digest)

    attachmentStream.url = this.url

    return attachmentStream
}

fun DatabaseAttachment.toSignalAttachmentPointer(): SignalServiceAttachmentPointer? {
    if (TextUtils.isEmpty(location)) { return null }
    // `key` can be empty in an open group context (no encryption means no encryption key)
    return try {
        val id = location?.toLongOrNull() ?: 0L
        val key = Base64.decode(key!!)
        SignalServiceAttachmentPointer(
            id,
            contentType,
            key,
            Optional.of(Util.toIntExact(size)),
            Optional.absent(),
            width,
            height,
            Optional.fromNullable(digest),
            filename,
            isVoiceNote,
            Optional.fromNullable(caption),
            url
        )
    } catch (e: Exception) {
        null
    }
}

fun DatabaseAttachment.toSignalAttachmentStream(context: Context): SignalServiceAttachmentStream {
    val stream = PartAuthority.getAttachmentStream(context, this.dataUri!!)

    return SignalServiceAttachmentStream(stream, this.contentType, this.size, this.filename, this.isVoiceNote, Optional.absent(), this.width, this.height, Optional.fromNullable(this.caption))
}


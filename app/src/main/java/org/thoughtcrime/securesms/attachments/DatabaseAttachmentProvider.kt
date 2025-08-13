package org.thoughtcrime.securesms.attachments

import android.content.Context
import android.text.TextUtils
import com.google.protobuf.ByteString
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
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.messages.SignalServiceAttachment
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.messages.SignalServiceAttachmentStream
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.Database
import org.thoughtcrime.securesms.database.MessagingDatabase
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.IOException
import java.io.InputStream
import javax.inject.Provider

class DatabaseAttachmentProvider(context: Context, helper: Provider<SQLCipherOpenHelper>) : Database(context, helper), MessageDataProvider {

    override fun getAttachmentStream(attachmentId: Long): SessionServiceAttachmentStream? {
        val attachmentDatabase = DatabaseComponent.get(context).attachmentDatabase()
        val databaseAttachment = attachmentDatabase.getAttachment(AttachmentId(attachmentId, 0)) ?: return null
        return databaseAttachment.toAttachmentStream(context)
    }

    override fun getAttachmentPointer(attachmentId: Long): SessionServiceAttachmentPointer? {
        val attachmentDatabase = DatabaseComponent.get(context).attachmentDatabase()
        val databaseAttachment = attachmentDatabase.getAttachment(AttachmentId(attachmentId, 0)) ?: return null
        return databaseAttachment.toAttachmentPointer()
    }

    override fun getSignalAttachmentStream(attachmentId: Long): SignalServiceAttachmentStream? {
        val attachmentDatabase = DatabaseComponent.get(context).attachmentDatabase()
        val databaseAttachment = attachmentDatabase.getAttachment(AttachmentId(attachmentId, 0)) ?: return null
        return databaseAttachment.toSignalAttachmentStream(context)
    }

    override fun getScaledSignalAttachmentStream(attachmentId: Long): SignalServiceAttachmentStream? {
        val database = DatabaseComponent.get(context).attachmentDatabase()
        val databaseAttachment = database.getAttachment(AttachmentId(attachmentId, 0)) ?: return null
        val mediaConstraints = MediaConstraints.getPushMediaConstraints()
        val scaledAttachment = scaleAndStripExif(database, mediaConstraints, databaseAttachment) ?: return null
        return getAttachmentFor(scaledAttachment)
    }

    override fun getSignalAttachmentPointer(attachmentId: Long): SignalServiceAttachmentPointer? {
        val attachmentDatabase = DatabaseComponent.get(context).attachmentDatabase()
        val databaseAttachment = attachmentDatabase.getAttachment(AttachmentId(attachmentId, 0)) ?: return null
        return databaseAttachment.toSignalAttachmentPointer()
    }

    override fun setAttachmentState(attachmentState: AttachmentState, attachmentId: AttachmentId, messageID: Long) {
        val attachmentDatabase = DatabaseComponent.get(context).attachmentDatabase()
        attachmentDatabase.setTransferState(messageID, attachmentId, attachmentState.value)
    }

    override fun getMessageForQuote(timestamp: Long, author: Address): Triple<Long, Boolean, String>? {
        val messagingDatabase = DatabaseComponent.get(context).mmsSmsDatabase()
        val message = messagingDatabase.getMessageFor(timestamp, author)
        return if (message != null) Triple(message.id, message.isMms, message.body) else null
    }

    override fun getAttachmentsAndLinkPreviewFor(mmsId: Long): List<Attachment> {
        return DatabaseComponent.get(context).attachmentDatabase().getAttachmentsForMessage(mmsId)
    }

    override fun getMessageBodyFor(timestamp: Long, author: String): String {
        val messagingDatabase = DatabaseComponent.get(context).mmsSmsDatabase()
        return messagingDatabase.getMessageFor(timestamp, author)!!.body
    }

    override fun getAttachmentIDsFor(mmsMessageId: Long): List<Long> {
        return DatabaseComponent.get(context)
            .attachmentDatabase()
            .getAttachmentsForMessage(mmsMessageId).mapNotNull {
            if (it.isQuote) return@mapNotNull null
            it.attachmentId.rowId
        }
    }

    override fun getLinkPreviewAttachmentIDFor(mmsMessageId: Long): Long? {
        val message = DatabaseComponent.get(context).mmsDatabase().getOutgoingMessage(mmsMessageId)
        return message.linkPreviews.firstOrNull()?.attachmentId?.rowId
    }

    override fun getIndividualRecipientForMms(mmsId: Long): Recipient? {
        val mmsDb = DatabaseComponent.get(context).mmsDatabase()
        val message = mmsDb.getMessage(mmsId).use {
            mmsDb.readerFor(it).next
        }
        return message?.individualRecipient
    }

    override fun insertAttachment(messageId: Long, attachmentId: AttachmentId, stream: InputStream) {
        val attachmentDatabase = DatabaseComponent.get(context).attachmentDatabase()
        attachmentDatabase.insertAttachmentsForPlaceholder(messageId, attachmentId, stream)
    }

    override fun updateAudioAttachmentDuration(
        attachmentId: AttachmentId,
        durationMs: Long,
        threadId: Long
    ) {
        val attachmentDb = DatabaseComponent.get(context).attachmentDatabase()
        attachmentDb.setAttachmentAudioExtras(DatabaseAttachmentAudioExtras(
            attachmentId = attachmentId,
            visualSamples = byteArrayOf(),
            durationMs = durationMs
        ), threadId)
    }

    override fun isOutgoingMessage(id: MessageId): Boolean {
        return if (id.mms) {
            DatabaseComponent.get(context).mmsDatabase().isOutgoingMessage(id.id)
        } else {
            DatabaseComponent.get(context).smsDatabase().isOutgoingMessage(id.id)
        }
    }

    override fun isDeletedMessage(id: MessageId): Boolean {
        return if (id.mms) {
            DatabaseComponent.get(context).mmsDatabase().isDeletedMessage(id.id)
        } else {
            DatabaseComponent.get(context).smsDatabase().isDeletedMessage(id.id)
        }
    }

    override fun handleSuccessfulAttachmentUpload(attachmentId: Long, attachmentStream: SignalServiceAttachmentStream, attachmentKey: ByteArray, uploadResult: UploadResult) {
        val database = DatabaseComponent.get(context).attachmentDatabase()
        val databaseAttachment = getDatabaseAttachment(attachmentId) ?: return
        val attachmentPointer = SignalServiceAttachmentPointer(uploadResult.id,
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
        database.updateAttachmentAfterUploadSucceeded(databaseAttachment.attachmentId, attachment)
    }

    override fun handleFailedAttachmentUpload(attachmentId: Long) {
        val database = DatabaseComponent.get(context).attachmentDatabase()
        val databaseAttachment = getDatabaseAttachment(attachmentId) ?: return
        database.handleFailedAttachmentUpload(databaseAttachment.attachmentId)
    }

    override fun getMessageID(serverId: Long, threadId: Long): MessageId? {
        val messageDB = DatabaseComponent.get(context).lokiMessageDatabase()
        return messageDB.getMessageID(serverId, threadId)
    }

    override fun getMessageIDs(serverIds: List<Long>, threadId: Long): Pair<List<Long>, List<Long>> {
        val messageDB = DatabaseComponent.get(context).lokiMessageDatabase()
        return messageDB.getMessageIDs(serverIds, threadId)
    }

    override fun getUserMessageHashes(threadId: Long, userPubKey: String): List<String> {
        val component = DatabaseComponent.get(context)
        val messages = component.mmsSmsDatabase().getUserMessages(threadId, userPubKey)
        val messageDatabase = component.lokiMessageDatabase()
        return messages.mapNotNull {
            messageDatabase.getMessageServerHash(it.messageId)
        }
    }

    override fun deleteMessage(messageId: MessageId) {
        if (messageId.mms) {
            DatabaseComponent.get(context).mmsDatabase().deleteMessage(messageId.id)
        } else {
            DatabaseComponent.get(context).smsDatabase().deleteMessage(messageId.id)
        }

        DatabaseComponent.get(context).lokiMessageDatabase().deleteMessage(messageId)
        DatabaseComponent.get(context).lokiMessageDatabase().deleteMessageServerHash(messageId)
    }

    override fun deleteMessages(messageIDs: List<Long>, threadId: Long, isSms: Boolean) {
        val messagingDatabase: MessagingDatabase = if (isSms)  DatabaseComponent.get(context).smsDatabase()
                                                   else DatabaseComponent.get(context).mmsDatabase()

        messagingDatabase.deleteMessages(messageIDs)
        DatabaseComponent.get(context).lokiMessageDatabase().deleteMessages(messageIDs, isSms = isSms)
        DatabaseComponent.get(context).lokiMessageDatabase().deleteMessageServerHashes(messageIDs, mms = !isSms)
    }

    override fun markMessageAsDeleted(messageId: MessageId, displayedMessage: String) {
        val database = DatabaseComponent.get(context).mmsSmsDatabase()
        val message = database.getMessageById(messageId) ?: return Log.w("", "Failed to find message to mark as deleted")

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
        val smsDatabase = DatabaseComponent.get(context).smsDatabase()
        val mmsDatabase = DatabaseComponent.get(context).mmsDatabase()

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
        val markAsDeleteMessages = DatabaseComponent.get(context).lokiMessageDatabase()
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
        val toDelete = DatabaseComponent.get(context).mmsSmsDatabase().getUserMessages(threadId, sender)
            .asSequence()
            .filter { it.timestamp <= until }
            .map { record ->
                MarkAsDeletedMessage(messageId = record.messageId, isOutgoing = record.isOutgoing)
            }
            .toList()

        markMessagesAsDeleted(toDelete, displayedMessage)
    }

    override fun getServerHashForMessage(messageID: MessageId): String? =
        DatabaseComponent.get(context).lokiMessageDatabase().getMessageServerHash(messageID)

    override fun getDatabaseAttachment(attachmentId: Long): DatabaseAttachment? =
        DatabaseComponent.get(context).attachmentDatabase()
            .getAttachment(AttachmentId(attachmentId, 0))

    private fun scaleAndStripExif(attachmentDatabase: AttachmentDatabase, constraints: MediaConstraints, attachment: Attachment): Attachment? {
        return try {
            if (constraints.isSatisfied(context, attachment)) {
                if (MediaUtil.isJpeg(attachment)) {
                    val stripped = constraints.getResizedMedia(context, attachment)
                    attachmentDatabase.updateAttachmentData(attachment, stripped)
                } else {
                    attachment
                }
            } else if (constraints.canResize(attachment)) {
                val resized = constraints.getResizedMedia(context, attachment)
                attachmentDatabase.updateAttachmentData(attachment, resized)
            } else {
                throw Exception("Size constraints could not be met!")
            }
        } catch (e: Exception) {
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
        val id = location!!.toLong()
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


package org.session.libsession.messaging.messages.visible

import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel as SignalQuote
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log

class Quote() {
    var timestamp: Long? = 0
    var publicKey: String? = null
    var text: String? = null
    var attachmentID: Long? = null

    fun isValid(): Boolean =  timestamp != null && publicKey != null

    companion object {
        const val TAG = "Quote"

        fun fromProto(proto: SignalServiceProtos.DataMessage.Quote): Quote? {
            val timestamp = proto.id
            val publicKey = proto.author
            val text = proto.text
            return Quote(timestamp, publicKey, text, null)
        }

        fun from(signalQuote: SignalQuote?): Quote? {
            if (signalQuote == null) { return null }
            val attachmentID = (signalQuote.attachments?.firstOrNull() as? DatabaseAttachment)?.attachmentId?.rowId
            return Quote(signalQuote.id, signalQuote.author.toString(), "", attachmentID)
        }
    }

    internal constructor(timestamp: Long, publicKey: String, text: String?, attachmentID: Long?) : this() {
        this.timestamp    = timestamp
        this.publicKey    = publicKey
        this.text         = text
        this.attachmentID = attachmentID
    }

    fun toProto(): SignalServiceProtos.DataMessage.Quote? {
        val timestamp = timestamp
        val publicKey = publicKey
        if (timestamp == null || publicKey == null) {
            Log.w(TAG, "Couldn't construct quote proto from: $this")
            return null
        }
        val quoteProto = SignalServiceProtos.DataMessage.Quote.newBuilder()
        quoteProto.id = timestamp
        quoteProto.author = publicKey
        text?.let { quoteProto.text = it }
        addAttachmentsIfNeeded(quoteProto)

        // Build
        try {
            return quoteProto.build()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't construct quote proto from: $this", e)
            return null
        }
    }

    private fun addAttachmentsIfNeeded(quoteProto: SignalServiceProtos.DataMessage.Quote.Builder) {
        val attachmentID = attachmentID ?: return Log.w(TAG, "Cannot add attachment with null attachmentID - bailing.")

        val database = MessagingModuleConfiguration.shared.messageDataProvider

        val pointer = database.getSignalAttachmentPointer(attachmentID)
        if (pointer == null) { return Log.w(TAG, "Ignoring invalid attachment for quoted message.") }

        if (pointer.url.isNullOrEmpty()) {
            return Log.w(TAG,"Cannot send a message before all associated attachments have been uploaded - bailing.")
        }

        val quotedAttachmentProto = SignalServiceProtos.DataMessage.Quote.QuotedAttachment.newBuilder()
        quotedAttachmentProto.contentType = pointer.contentType
        quotedAttachmentProto.fileName    = pointer.filename
        quotedAttachmentProto.thumbnail   = Attachment.createAttachmentPointer(pointer)

        try {
            quoteProto.addAttachments(quotedAttachmentProto.build())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't construct quoted attachment proto from: $this", e)
        }
    }
}
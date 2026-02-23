package org.session.libsession.messaging.messages.visible

import androidx.annotation.Keep
import org.session.libsession.database.MessageDataProvider
import org.session.libsignal.utilities.Log
import org.session.protos.SessionProtos
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview as SignalLinkPreiview

@Keep
class LinkPreview() {
    var title: String? = null
    var url: String? = null
    var attachmentID: Long? = 0

    fun isValid(): Boolean {
        return (title != null && url != null && attachmentID != null)
    }

    companion object {
        const val TAG = "LinkPreview"

        fun fromProto(proto: SessionProtos.DataMessage.Preview): LinkPreview? {
            val title = proto.title
            val url = proto.url
            return LinkPreview(title, url, null)
        }

        fun from(signalLinkPreview: SignalLinkPreiview?): LinkPreview? {
            if (signalLinkPreview == null) { return null }
            return LinkPreview(signalLinkPreview.title, signalLinkPreview.url, signalLinkPreview.attachmentId?.rowId)
        }
    }

    internal constructor(title: String?, url: String, attachmentID: Long?) : this() {
        this.title = title
        this.url = url
        this.attachmentID = attachmentID
    }

    fun toProto(messageDataProvider: MessageDataProvider): SessionProtos.DataMessage.Preview? {
        val url = url
        if (url == null) {
            Log.w(TAG, "Couldn't construct link preview proto from: $this")
            return null
        }
        val linkPreviewProto = SessionProtos.DataMessage.Preview.newBuilder()
        linkPreviewProto.url = url
        title?.let { linkPreviewProto.title = it }
        attachmentID?.let {
            messageDataProvider.getSignalAttachmentPointer(it)?.let {
                val attachmentProto = Attachment.createAttachmentPointer(it)
                linkPreviewProto.image = attachmentProto
            }
        }
        // Build
        try {
            return linkPreviewProto.build()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't construct link preview proto from: $this")
            return null
        }
    }
}
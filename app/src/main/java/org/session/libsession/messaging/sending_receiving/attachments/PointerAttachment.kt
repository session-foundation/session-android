package org.session.libsession.messaging.sending_receiving.attachments

import android.net.Uri
import org.session.libsignal.messages.SignalServiceAttachment
import org.session.libsignal.utilities.Base64
import org.session.protos.SessionProtos

class PointerAttachment private constructor(
    contentType: String,
    transferState: Int,
    size: Long,
    fileName: String?,
    location: String,
    key: String?,
    relay: String?,
    digest: ByteArray?,
    fastPreflightId: String?,
    voiceNote: Boolean,
    width: Int,
    height: Int,
    caption: String?,
    url: String
) : Attachment(
    contentType,
    transferState,
    size,
    fileName,
    location,
    key,
    relay,
    digest,
    fastPreflightId,
    voiceNote,
    width,
    height,
    false,
    caption,
    url,
    -1L
) {

    override fun getDataUri(): Uri? = null

    override fun getThumbnailUri(): Uri? = null

    companion object {

        @JvmStatic
        fun forPointers(pointers: List<SignalServiceAttachment>?): List<Attachment> {
            return pointers.orEmpty().mapNotNull { forPointer(it) }
        }

        @JvmStatic
        fun forPointers(pointers: List<SessionProtos.DataMessage.Quote.QuotedAttachment>?): List<Attachment> {
            return pointers.orEmpty().mapNotNull { forPointer(it) }
        }

        @JvmStatic
        fun forPointer(pointer: SignalServiceAttachment?): Attachment? {
            return forPointer(pointer, null)
        }

        @JvmStatic
        fun forPointer(pointer: SignalServiceAttachment?, fastPreflightId: String?): Attachment? {
            if (pointer == null || !pointer.isPointer()) return null

            val p = pointer.asPointer()

            val encodedKey: String? = Base64.encodeBytes(p.key)

            return PointerAttachment(
                pointer.contentType,
                AttachmentState.PENDING.value,
                p.size?.toLong() ?: 0L,
                p.filename,
                p.id.toString(),
                encodedKey,
                null,
                p.digest,
                fastPreflightId,
                p.voiceNote,
                p.width,
                p.height,
                p.caption,
                p.url
            )
        }

        @JvmStatic
        fun forPointer(pointer: SessionProtos.AttachmentPointer?): Attachment? {
            // Old code would NPE on pointer.getContentType() if pointer == null,
            // but also had a pointless null-check later. This makes it safe:
            pointer ?: return null

            return PointerAttachment(
                pointer.contentType,
                AttachmentState.PENDING.value,
                pointer.size.toLong(),
                pointer.fileName,
                pointer.id.toString(),
                pointer.key?.let { Base64.encodeBytes(it.toByteArray()) },
                null,
                pointer.digest.toByteArray(),
                null,
                false,
                pointer.width,
                pointer.height,
                pointer.caption,
                pointer.url
            )
        }

        @JvmStatic
        fun forPointer(pointer: SessionProtos.DataMessage.Quote.QuotedAttachment?): Attachment? {
            pointer ?: return null
            val thumbnail = pointer.thumbnail

            // Match existing behavior: if thumbnail is null, use defaults and url = ""
            return PointerAttachment(
                pointer.contentType,
                AttachmentState.PENDING.value,
                thumbnail?.size?.toLong() ?: 0L,
                thumbnail?.fileName,
                (thumbnail?.id ?: 0).toString(),
                thumbnail?.key?.let { Base64.encodeBytes(it.toByteArray()) },
                null,
                thumbnail?.digest?.toByteArray(),
                null,
                false,
                thumbnail?.width ?: 0,
                thumbnail?.height ?: 0,
                thumbnail?.caption,
                thumbnail?.url ?: ""
            )
        }

        /**
         * Converts a Session Attachment to a Signal Attachment
         */
        @JvmStatic
        fun forAttachment(
            attachment: org.session.libsession.messaging.messages.visible.Attachment
        ): Attachment {
            return PointerAttachment(
                attachment.contentType ?: "",
                AttachmentState.PENDING.value,
                attachment.sizeInBytes?.toLong() ?: 0L,
                attachment.filename,
                "",
                attachment.key?.let{ Base64.encodeBytes(it) },
                null,
                attachment.digest,
                null,
                attachment.kind == org.session.libsession.messaging.messages.visible.Attachment.Kind.VOICE_MESSAGE,
                attachment.size?.width ?: 0,
                attachment.size?.height ?: 0,
                attachment.caption,
                attachment.url ?: ""
            )
        }
    }
}

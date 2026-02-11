package org.session.libsession.messaging.messages.visible

import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import com.google.protobuf.ByteString
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.protos.SessionProtos
import java.io.File
import org.session.libsession.messaging.sending_receiving.attachments.Attachment as SignalAttachment

class Attachment {
    var filename: String? = null
    var contentType: String? = null
    var key: ByteArray? = null
    var digest: ByteArray? = null
    var kind: Kind? = null
    var caption: String? = null
    var size: Size? = null
    var sizeInBytes: Int? = 0
    var url: String? = null

    companion object {

        fun fromProto(proto: SessionProtos.AttachmentPointer): Attachment {
            val result = Attachment()

            // Note: For legacy Session Android clients this filename will be null and we'll synthesise an appropriate filename
            // further down the stack in the Storage.persist
            result.filename = proto.fileName

            fun inferContentType(): String {
                val fileName = result.filename.orEmpty()
                val fileExtension = File(fileName).extension
                val mimeTypeMap = MimeTypeMap.getSingleton()
                return mimeTypeMap.getMimeTypeFromExtension(fileExtension) ?: "application/octet-stream"
            }
            result.contentType = proto.contentType ?: inferContentType()

            result.key = proto.key.toByteArray()
            result.digest = proto.digest.toByteArray()

            result.kind =
                if (proto.hasFlags() && (proto.flags and SessionProtos.AttachmentPointer.Flags.VOICE_MESSAGE_VALUE) > 0) {
                    Kind.VOICE_MESSAGE
                } else {
                    Kind.GENERIC
                }

            result.caption = if (proto.hasCaption()) proto.caption else null

            result.size =
                if (proto.hasWidth() && proto.width > 0 && proto.hasHeight() && proto.height > 0) {
                    Size(proto.width, proto.height)
                } else {
                    Size(0, 0)
                }

            result.sizeInBytes = if (proto.size > 0) proto.size else null
            result.url = proto.url

            return result
        }

        fun createAttachmentPointer(attachment: SignalServiceAttachmentPointer): SessionProtos.AttachmentPointer {
            val builder = SessionProtos.AttachmentPointer.newBuilder()
                .setContentType(attachment.contentType)
                .setId(attachment.id)
                .setKey(ByteString.copyFrom(attachment.key))
                .setSize(attachment.size ?: 0)
                .setUrl(attachment.url)

            attachment.digest?.let { builder.setDigest(ByteString.copyFrom(it)) }

            // Filenames are now mandatory for picked/shared files, Giphy GIFs, and captured photos.
            // The images associated with LinkPreviews don't have a "given name" so we'll use the
            // attachment ID as the filename. It's not possible to save these preview images or see
            // the filename, so what the filename IS isn't important, only that a filename exists.
            builder.fileName = attachment.filename ?: attachment.id.toString()

            attachment.preview?.let { builder.thumbnail = ByteString.copyFrom(it) }
            if (attachment.width > 0) builder.width = attachment.width
            if (attachment.height > 0) builder.height = attachment.height
            if (attachment.voiceNote) builder.flags = SessionProtos.AttachmentPointer.Flags.VOICE_MESSAGE_VALUE
            attachment.caption?.let { builder.caption = it }

            return builder.build()
        }
    }

    enum class Kind {
        VOICE_MESSAGE,
        GENERIC
    }

    fun isValid(): Boolean {
        // key and digest can be nil for open group attachments
        return (contentType != null && kind != null && size != null && sizeInBytes != null && url != null)
    }

    fun toProto(): SessionProtos.AttachmentPointer? {
        TODO("Not implemented")
    }

    fun toSignalAttachment(): SignalAttachment? {
        if (!isValid()) return null
        return PointerAttachment.forAttachment(this)
    }

    fun toSignalPointer(): SignalServiceAttachmentPointer? {
        if (!isValid()) return null

        // safe because isValid() checked them
        val ct = contentType!!
        val k = key!!
        val u = url!!
        val file = filename

        return SignalServiceAttachmentPointer(
            id = 0L,
            contentType = ct,
            key = k,
            size = sizeInBytes,      // Int?
            preview = null,          // ByteArray?
            width = size?.width ?: 0,
            height = size?.height ?: 0,
            digest = digest,         // ByteArray?
            filename = file ?: "",   // OR keep nullable in pointer; see note below
            voiceNote = (kind == Kind.VOICE_MESSAGE),
            caption = caption,       // String?
            url = u
        )
    }
}

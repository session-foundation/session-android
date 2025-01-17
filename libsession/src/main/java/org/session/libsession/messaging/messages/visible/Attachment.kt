package org.session.libsession.messaging.messages.visible

import android.util.Size
import android.webkit.MimeTypeMap
import com.google.protobuf.ByteString
import org.session.libsession.messaging.sending_receiving.attachments.Attachment as SignalAttachment
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.protos.SignalServiceProtos
import java.io.File

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

        fun fromProto(proto: SignalServiceProtos.AttachmentPointer): Attachment {
            val result = Attachment()
            result.filename = if (proto.hasFileName()) proto.fileName else "SessionAttachment"
            fun inferContentType(): String {
                val fileName = result.filename
                val fileExtension = File(fileName).extension
                val mimeTypeMap = MimeTypeMap.getSingleton()
                return mimeTypeMap.getMimeTypeFromExtension(fileExtension) ?: "application/octet-stream"
            }
            result.contentType = proto.contentType ?: inferContentType()
            result.key = proto.key.toByteArray()
            result.digest = proto.digest.toByteArray()
            val kind: Kind = if (proto.hasFlags() && proto.flags.and(SignalServiceProtos.AttachmentPointer.Flags.VOICE_MESSAGE_VALUE) > 0) {
                Kind.VOICE_MESSAGE
            } else {
                Kind.GENERIC
            }
            result.kind = kind
            result.caption = if (proto.hasCaption()) proto.caption else null
            val size: Size = if (proto.hasWidth() && proto.width > 0 && proto.hasHeight() && proto.height > 0) {
                Size(proto.width, proto.height)
            } else {
                Size(0,0)
            }
            result.size = size
            result.sizeInBytes = if (proto.size > 0) proto.size else null
            result. url = proto.url
            return result
        }

        fun createAttachmentPointer(attachment: SignalServiceAttachmentPointer): SignalServiceProtos.AttachmentPointer? {
            val builder = SignalServiceProtos.AttachmentPointer.newBuilder()
                    .setContentType(attachment.contentType)
                    .setId(attachment.id)
                    .setKey(ByteString.copyFrom(attachment.key))
                    .setDigest(ByteString.copyFrom(attachment.digest.get()))
                    .setSize(attachment.size.get())
                    .setUrl(attachment.url)

            // Filenames are now mandatory
            builder.fileName = attachment.filename

            if (attachment.preview.isPresent) { builder.thumbnail = ByteString.copyFrom(attachment.preview.get())               }
            if (attachment.width > 0)         { builder.width = attachment.width                                                }
            if (attachment.height > 0)        { builder.height = attachment.height                                              }
            if (attachment.voiceNote)         { builder.flags = SignalServiceProtos.AttachmentPointer.Flags.VOICE_MESSAGE_VALUE }
            if (attachment.caption.isPresent) { builder.caption = attachment.caption.get()                                      }

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

    fun toProto(): SignalServiceProtos.AttachmentPointer? {
        TODO("Not implemented")
    }

    fun toSignalAttachment(): SignalAttachment? {
        if (!isValid()) return null
        return PointerAttachment.forAttachment((this))
    }

    fun toSignalPointer(): SignalServiceAttachmentPointer? {
        if (!isValid()) return null
        return SignalServiceAttachmentPointer(0, contentType, key, Optional.fromNullable(sizeInBytes), null,
                size?.width ?: 0, size?.height ?: 0, Optional.fromNullable(digest), filename,
                kind == Kind.VOICE_MESSAGE, Optional.fromNullable(caption), url)
    }

}
package org.session.libsession.messaging.messages.visible

import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import com.google.protobuf.ByteString
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.guava.Optional
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

        fun fromProto(proto: SignalServiceProtos.AttachmentPointer): Attachment {
            val result = Attachment()

            // Note: For legacy Session Android clients this filename will be null and we'll synthesise an appropriate filename
            // further down the stack
            result.filename = proto.fileName

            fun inferContentType(): String {
                val fileName = result.filename
                val fileExtension = File(fileName).extension
                val mimeTypeMap = MimeTypeMap.getSingleton()
                return mimeTypeMap.getMimeTypeFromExtension(fileExtension) ?: "application/octet-stream"
            }
            result.contentType = proto.contentType ?: inferContentType()

            // If we were given a null filename from a legacy client but we at least have a content type (i.e., mime type)
            // then the best we can do is synthesise a filename based on the content type and when we received the file.
            if (result.filename.isNullOrEmpty() && !result.contentType.isNullOrEmpty()) {
                Log.d("", "*** GOT an empty filename")
                //result.filename = generateFilenameFromReceivedTypeForLegacyClients(result.contentType!!)
                //todo: Found this part with the code commented out... This 'if' is now doing nothing at all, is that normal? Shouldn't we indeed set a filename here or is that handled further down the line? (can't explore this now so I'm leaving a todo)
            }

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
            result.url = proto.url

            return result
        }

        fun createAttachmentPointer(attachment: SignalServiceAttachmentPointer): SignalServiceProtos.AttachmentPointer? {
            val builder = SignalServiceProtos.AttachmentPointer.newBuilder()
                    .setContentType(attachment.contentType)
                    .setId(attachment.id.toString().toLongOrNull() ?: 0L)
                    .setKey(ByteString.copyFrom(attachment.key))
                    .setDigest(ByteString.copyFrom(attachment.digest.get()))
                    .setSize(attachment.size.get())
                    .setUrl(attachment.url)
            
            // Filenames are now mandatory for picked/shared files, Giphy GIFs, and captured photos.
            // The images associated with LinkPreviews don't have a "given name" so we'll use the
            // attachment ID as the filename. It's not possible to save these preview images or see
            // the filename, so what the filename IS isn't important, only that a filename exists.
            builder.fileName = attachment.filename ?: attachment.id.toString()

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
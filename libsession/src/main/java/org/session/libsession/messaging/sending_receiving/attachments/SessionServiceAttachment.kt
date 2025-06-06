package org.session.libsession.messaging.sending_receiving.attachments

import com.google.protobuf.ByteString
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.messages.SignalServiceAttachment
import java.io.InputStream

abstract class SessionServiceAttachment protected constructor(val contentType: String?) {

    var attachmentId: Long = 0
    var isGif: Boolean = false
    var isImage: Boolean = false
    var isVideo: Boolean = false
    var isAudio: Boolean = false
    var url: String = ""
    var key: ByteString? = null

    abstract fun isStream(): Boolean
    abstract fun isPointer(): Boolean
    fun asStream(): SessionServiceAttachmentStream {
        return this as SessionServiceAttachmentStream
    }

    fun asPointer(): SessionServiceAttachmentPointer {
        return this as SessionServiceAttachmentPointer
    }

    fun shouldHaveImageSize(): Boolean {
        return (isVideo || isImage || isGif);
    }

    class Builder internal constructor() {
        private var inputStream: InputStream? = null
        private var contentType: String? = null
        private var filename: String = "PlaceholderFilename"
        private var length: Long = 0
        private var voiceNote = false
        private var width = 0
        private var height = 0
        private var caption: String? = null
        fun withStream(inputStream: InputStream?): Builder {
            this.inputStream = inputStream
            return this
        }

        fun withContentType(contentType: String?): Builder {
            this.contentType = contentType
            return this
        }

        fun withLength(length: Long): Builder {
            this.length = length
            return this
        }

        fun withFilename(filename: String): Builder {
            this.filename = filename
            return this
        }

        fun withVoiceNote(voiceNote: Boolean): Builder {
            this.voiceNote = voiceNote
            return this
        }

        fun withWidth(width: Int): Builder {
            this.width = width
            return this
        }

        fun withHeight(height: Int): Builder {
            this.height = height
            return this
        }

        fun withCaption(caption: String?): Builder {
            this.caption = caption
            return this
        }

        fun build(): SessionServiceAttachmentStream {
            requireNotNull(inputStream) { "Must specify stream!" }
            requireNotNull(contentType) { "No content type specified!" }
            require(length != 0L) { "No length specified!" }
            return SessionServiceAttachmentStream(inputStream, contentType, length, filename, voiceNote, Optional.absent(), width, height, Optional.fromNullable(caption))
        }
    }

    companion object {
        @JvmStatic
        fun newStreamBuilder(): Builder {
            return Builder()
        }
    }
}

enum class AttachmentState(val value: Int) {
    DONE(0),
    DOWNLOADING(1),
    PENDING(2),
    FAILED(3),
    EXPIRED(4)
}
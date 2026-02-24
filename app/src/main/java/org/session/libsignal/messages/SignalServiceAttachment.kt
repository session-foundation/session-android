package org.session.libsignal.messages

import java.io.InputStream

abstract class SignalServiceAttachment(
    val contentType: String
) {

    abstract fun isStream(): Boolean
    abstract fun isPointer(): Boolean

    fun asStream(): SignalServiceAttachmentStream =
        this as SignalServiceAttachmentStream

    fun asPointer(): SignalServiceAttachmentPointer =
        this as SignalServiceAttachmentPointer

    companion object {
        fun newStreamBuilder(): Builder = Builder()
    }

    class Builder {

        private var inputStream: InputStream? = null
        private var contentType: String? = null
        private var filename: String? = null
        private var length: Long? = null
        private var voiceNote: Boolean = false
        private var width: Int = 0
        private var height: Int = 0
        private var caption: String? = null

        fun withStream(inputStream: InputStream) = apply {
            this.inputStream = inputStream
        }

        fun withContentType(contentType: String) = apply {
            this.contentType = contentType
        }

        fun withLength(length: Long) = apply {
            this.length = length
        }

        fun withFileName(filename: String?) = apply {
            this.filename = filename
        }

        fun withVoiceNote(voiceNote: Boolean) = apply {
            this.voiceNote = voiceNote
        }

        fun withWidth(width: Int) = apply {
            this.width = width
        }

        fun withHeight(height: Int) = apply {
            this.height = height
        }

        fun withCaption(caption: String?) = apply {
            this.caption = caption
        }

        fun build(): SignalServiceAttachmentStream {
            val stream = inputStream
                ?: throw IllegalArgumentException("Must specify stream!")

            val type = contentType
                ?: throw IllegalArgumentException("No content type specified!")

            val len = length
                ?: throw IllegalArgumentException("No length specified!")

            require(len > 0) { "No length specified!" }

            return SignalServiceAttachmentStream(
                inputStream = stream,
                contentType = type,
                length = len,
                filename = filename,
                voiceNote = voiceNote,
                preview = null,
                width = width,
                height = height,
                caption = caption
            )
        }
    }
}

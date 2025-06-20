/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */
package org.session.libsession.messaging.sending_receiving.attachments

import android.util.Size
import com.google.protobuf.ByteString
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.messages.SignalServiceAttachment as SAttachment
import java.io.InputStream
import kotlin.math.round

/**
 * Represents a local SignalServiceAttachment to be sent.
 */
class SessionServiceAttachmentStream(val inputStream: InputStream?, contentType: String?, val length: Long, val filename: String, val voiceNote: Boolean, val preview: Optional<ByteArray?>, val width: Int, val height: Int, val caption: Optional<String?>) : SessionServiceAttachment(contentType) {

    constructor(inputStream: InputStream?, contentType: String?, length: Long, filename: String, voiceNote: Boolean) : this(inputStream, contentType, length, filename, voiceNote, Optional.absent<ByteArray?>(), 0, 0, Optional.absent<String?>()) {}

    // Though now required, `digest` may be null for pre-existing records or from
    // messages received from other clients
    var digest: Optional<ByteArray> = Optional.absent()

    // This only applies for attachments being uploaded.
    var isUploaded: Boolean = false

    override fun isStream(): Boolean {
        return true
    }

    override fun isPointer(): Boolean {
        return false
    }

    fun toProto(): SignalServiceProtos.AttachmentPointer? {
        val builder = SignalServiceProtos.AttachmentPointer.newBuilder()
        builder.contentType = this.contentType
        builder.fileName = this.filename

        if (!this.caption.get().isNullOrEmpty()) {
            builder.caption = this.caption.get()
        }

        builder.size = this.length.toInt()
        builder.key = this.key
        builder.digest = ByteString.copyFrom(this.digest.get())
        builder.flags = if (this.voiceNote) SignalServiceProtos.AttachmentPointer.Flags.VOICE_MESSAGE.number else 0

        //TODO I did copy the behavior of iOS below, not sure if that's relevant here...
        if (this.shouldHaveImageSize()) {
            if (this.width < Int.MAX_VALUE && this.height < Int.MAX_VALUE) {
                val imageSize= Size(this.width, this.height)
                val imageWidth = round(imageSize.width.toDouble())
                val imageHeight = round(imageSize.height.toDouble())
                if (imageWidth > 0 && imageHeight > 0) {
                    builder.width = imageWidth.toInt()
                    builder.height = imageHeight.toInt()
                }
            }
        }

        builder.url = this.url

        try {
            return builder.build()
        } catch (e: Exception) {
            return null
        }
    }
}

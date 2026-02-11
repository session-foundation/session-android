package org.session.libsignal.messages

import java.io.InputStream

/**
 * Represents a local SignalServiceAttachment to be sent.
 */
class SignalServiceAttachmentStream(
    val inputStream: InputStream,
    contentType: String,
    val length: Long,
    val filename: String,
    val voiceNote: Boolean,
    val preview: ByteArray?,
    val width: Int,
    val height: Int,
    val caption: String?
) : SignalServiceAttachment(contentType) {

    override fun isStream(): Boolean = true

    override fun isPointer(): Boolean = false
}

package org.session.libsignal.messages

/**
 * Represents a received SignalServiceAttachment "handle." This
 * is a pointer to the actual attachment content, which needs to be
 * retrieved using SignalServiceMessageReceiver.retrieveAttachment(...)
 */
class SignalServiceAttachmentPointer(
    val id: Long,
    contentType: String,
    val key: ByteArray,
    val size: Int?,
    val preview: ByteArray?,
    val width: Int,
    val height: Int,
    val digest: ByteArray?,
    val filename: String?,
    val voiceNote: Boolean,
    val caption: String?,
    val url: String
) : SignalServiceAttachment(contentType) {

    override fun isStream(): Boolean = false
    override fun isPointer(): Boolean = true
}

package org.thoughtcrime.securesms.audio.model

import android.net.Uri

/**
 * Thin playback model derived from Slide/Attachment.
 * This is NOT a new domain model; it's just what the playback layer needs.
 */
data class PlayableAudio(
    val key: Key,
    val uri: Uri,
    val isVoiceNote: Boolean,
    val durationMs: Long, // from Attachment.audioDurationMs, may be -1
    val title: String?,
    val artist: String?, // e.g. sender name for voice notes
) {
    data class Key(
        val messageId: Long,
        val attachmentId: String? = null, //todo AUDIO attachment ids can be null, do we need a better alternative?
    ) {
        fun asMediaId(): String = if (attachmentId != null) "msg:$messageId:att:$attachmentId" else "msg:$messageId"
    }
}

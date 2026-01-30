package org.thoughtcrime.securesms.audio.model

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

object MediaItemFactory {
    const val EXTRA_IS_VOICE = "audio.is_voice"
    const val EXTRA_DURATION_HINT = "audio.duration_hint"
    const val EXTRA_THREAD_ADDRESS = "audio.thread_address"
    const val EXTRA_MESSAGE_ID = "audio.message_id"
    const val EXTRA_SENDER_NAME = "audio.sender_name"
    const val EXTRA_FILENAME = "audio.filename"

    /**
     * @param extracted Optional metadata extracted from the file (ID3 tags).
     * If null, fast defaults (Sender/Filename) are used.
     */
    fun fromPlayable(audio: PlayableAudio): MediaItem {
        val extras = Bundle().apply {
            putBoolean(EXTRA_IS_VOICE, audio.isVoiceNote)
            putLong(EXTRA_DURATION_HINT, audio.durationMs)
            putParcelable(EXTRA_THREAD_ADDRESS, audio.thread)
            putParcelable(EXTRA_MESSAGE_ID, audio.messageId)
            putString(EXTRA_SENDER_NAME, audio.senderName)
            putString(EXTRA_FILENAME, audio.filename)
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setExtras(extras)

        return MediaItem.Builder()
            .setUri(audio.uri)
            .setMediaId(audio.messageId.serialize())
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    fun isVoice(mediaItem: MediaItem?): Boolean =
        mediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_IS_VOICE, false) ?: false

    fun withMetadata(item: MediaItem, newMetadata: MediaMetadata): MediaItem {
        return item.buildUpon().setMediaMetadata(newMetadata).build()
    }
}

package org.thoughtcrime.securesms.audio.model

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

object MediaItemFactory {
    const val EXTRA_IS_VOICE = "audio.is_voice"
    const val EXTRA_DURATION_HINT = "audio.duration_hint"
    const val EXTRA_THREAD_ADDRESS = "audio.thread_address"
    const val EXTRA_MESSAGE_ID = "audio.message_id"

    fun fromPlayable(audio: PlayableAudio): MediaItem {
        val extras = Bundle().apply {
            putBoolean(EXTRA_IS_VOICE, audio.isVoiceNote)
            putLong(EXTRA_DURATION_HINT, audio.durationMs)
            putParcelable(EXTRA_THREAD_ADDRESS, audio.thread)
            putParcelable(EXTRA_MESSAGE_ID, audio.messageId)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(audio.title ?: "Audio")
            .setArtist(audio.artist)
            .setDisplayTitle(audio.title ?: audio.artist ?: "Audio")
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setUri(audio.uri)
            .setMediaId(audio.messageId.serialize())
            .setMediaMetadata(metadata)
            .build()
    }

    fun isVoice(mediaItem: MediaItem?): Boolean =
        mediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_IS_VOICE, false) ?: false

    fun withArtwork(item: MediaItem, artworkData: ByteArray): MediaItem {
        val newMetadata = item.mediaMetadata.buildUpon() // buildUpon preserves existing extras
            .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        return item.buildUpon()
            .setMediaMetadata(newMetadata)
            .build()
    }
}

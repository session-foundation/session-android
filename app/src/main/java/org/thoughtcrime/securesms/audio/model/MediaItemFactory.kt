package org.thoughtcrime.securesms.audio.model

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

object MediaItemFactory {

    private const val EXTRA_IS_VOICE = "audio.is_voice"
    private const val EXTRA_DURATION_HINT = "audio.duration_hint"

    fun fromPlayable(audio: PlayableAudio): MediaItem {
        val extras = Bundle().apply {
            putBoolean(EXTRA_IS_VOICE, audio.isVoiceNote)
            putLong(EXTRA_DURATION_HINT, audio.durationMs)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(audio.title ?: "Audio")
            .setArtist(audio.artist)
            .setDisplayTitle(audio.title ?: audio.artist ?: "Audio")
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setUri(audio.uri)
            .setMediaId(audio.key.asMediaId())
            .setMediaMetadata(metadata)
            .build()
    }

    fun isVoice(mediaItem: MediaItem?): Boolean {
        return mediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_IS_VOICE, false) ?: false
    }
}

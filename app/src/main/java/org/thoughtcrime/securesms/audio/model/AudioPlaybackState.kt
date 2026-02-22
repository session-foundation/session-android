package org.thoughtcrime.securesms.audio.model

/**
 * Playback state exposed to UI.
 */
sealed class AudioPlaybackState(
    open val playbackSpeed: Float
) {
    fun playbackSpeedFormatted(): String {
        return when (playbackSpeed) {
            1f -> "1x"
            1.5f -> "1.5x"
            2f -> "2x"
            0.5f -> ".5x"
            else -> "-"
        }
    }

    data class Idle(override val playbackSpeed: Float = 1f) : AudioPlaybackState(playbackSpeed)

    sealed class Active(
        open val playable: PlayableAudio,
        open val positionMs: Long,
        open val durationMs: Long,
        playbackSpeed: Float
    ) : AudioPlaybackState(playbackSpeed) {

        fun senderOrFile() = playable.senderName ?: playable.filename


        data class Loading(
            override val playable: PlayableAudio,
            override val playbackSpeed: Float
        ) : Active(playable, 0, 0, playbackSpeed)

        data class Playing(
            override val playable: PlayableAudio,
            override val positionMs: Long,
            override val durationMs: Long,
            val bufferedPositionMs: Long,
            override val playbackSpeed: Float,
            val isBuffering: Boolean
        ) : Active(playable, positionMs, durationMs, playbackSpeed)

        data class Paused(
            override val playable: PlayableAudio,
            override val positionMs: Long,
            override val durationMs: Long,
            val bufferedPositionMs: Long,
            override val playbackSpeed: Float,
            val isBuffering: Boolean
        ) : Active(playable, positionMs, durationMs, playbackSpeed)

        data class Error(
            override val playable: PlayableAudio,
            val message: String,
            override val playbackSpeed: Float
        ) : Active(playable, 0, 0, playbackSpeed)
    }
}

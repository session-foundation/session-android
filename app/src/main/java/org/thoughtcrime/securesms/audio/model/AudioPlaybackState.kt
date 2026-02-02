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

    data object Idle : AudioPlaybackState(1f)

    data class Loading(
        val playable: PlayableAudio,
        override val playbackSpeed: Float
    ) : AudioPlaybackState(playbackSpeed)

    data class Playing(
        val playable: PlayableAudio,
        val positionMs: Long,
        val durationMs: Long,
        val bufferedPositionMs: Long,
        override val playbackSpeed: Float,
        val isBuffering: Boolean
    ) : AudioPlaybackState(playbackSpeed)

    data class Paused(
        val playable: PlayableAudio,
        val positionMs: Long,
        val durationMs: Long,
        val bufferedPositionMs: Long,
        override val playbackSpeed: Float,
        val isBuffering: Boolean
    ) : AudioPlaybackState(playbackSpeed)

    data class Error(
        val playable: PlayableAudio,
        val message: String,
        override val playbackSpeed: Float
    ) : AudioPlaybackState(playbackSpeed)
}

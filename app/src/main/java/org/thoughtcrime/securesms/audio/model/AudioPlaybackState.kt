package org.thoughtcrime.securesms.audio.model

/**
 * Playback state exposed to UI.
 */
sealed class AudioPlaybackState {
    data object Idle : AudioPlaybackState()
    data class Loading(val playable: PlayableAudio) : AudioPlaybackState()

    data class Playing(
        val playable: PlayableAudio,
        val positionMs: Long,
        val durationMs: Long,
        val bufferedPositionMs: Long,
        val playbackSpeed: Float,
        val isBuffering: Boolean
    ) : AudioPlaybackState(){
        override fun playbackSpeedFormatted(): String {
            return when (playbackSpeed) {
                1f -> "1x"
                1.5f -> "1.5x"
                2f -> "2x"
                0.5f -> ".5x"
                else -> "-"
            }
        }
    }

    data class Paused(
        val playable: PlayableAudio,
        val positionMs: Long,
        val durationMs: Long,
        val bufferedPositionMs: Long,
        val playbackSpeed: Float,
        val isBuffering: Boolean
    ) : AudioPlaybackState()

    data class Error(val playable: PlayableAudio, val message: String) : AudioPlaybackState()

    open fun playbackSpeedFormatted(): String {
        return DEFAULT_PLAYBACK_SPEED_DISPLAY
    }

    companion object {
        const val DEFAULT_PLAYBACK_SPEED_DISPLAY = "1x"
    }
}

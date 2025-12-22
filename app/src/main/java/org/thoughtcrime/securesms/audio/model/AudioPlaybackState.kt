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
    ) : AudioPlaybackState()

    data class Paused(
        val playable: PlayableAudio,
        val positionMs: Long,
        val durationMs: Long,
        val bufferedPositionMs: Long,
        val playbackSpeed: Float,
        val isBuffering: Boolean
    ) : AudioPlaybackState()

    data class Error(val playable: PlayableAudio, val message: String) : AudioPlaybackState()
}

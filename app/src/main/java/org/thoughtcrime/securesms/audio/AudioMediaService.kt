package org.thoughtcrime.securesms.audio

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.audio.model.AudioCommands
import org.thoughtcrime.securesms.audio.model.MediaItemFactory

@AndroidEntryPoint
class AudioMediaService : MediaSessionService() {
    //todo AUDIO notification: add sender image in bg or track image if possible?
    //todo AUDIO notification: add app icon in top left
    //todo AUDIO notification: add back seek / restart button

    private val TAG = "AudioMediaService"

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Media-style notification + lockscreen controls handled by MediaSessionService.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
        )

        player = ExoPlayer.Builder(this).build().apply {
            addListener(servicePlayerListener)
        }

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()

        // Apply policy for the initial item if any controller sets it very quickly.
        applyAudioPolicyForCurrentItem()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null

        player.removeListener(servicePlayerListener)
        player.release()

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop service if not actively playing when app is swiped away.
        if (!player.isPlaying) {
            player.stop()
            stopSelf()
        }
    }

    // --- Player policy (voice vs music) ---

    private fun applyAudioPolicyForCurrentItem() {
        val isVoice = MediaItemFactory.isVoice(player.currentMediaItem)

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(if (isVoice) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(if (isVoice) C.USAGE_VOICE_COMMUNICATION else C.USAGE_MEDIA)
            .build()

        player.setAudioAttributes(attrs, /* handleAudioFocus = */ true)
    }

    private val servicePlayerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyAudioPolicyForCurrentItem()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // If nothing queued, stop service (notification goes away).
            if (playbackState == Player.STATE_IDLE && player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    // --- MediaSession callback ---

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)

            // Tighten commands for the system notification controller (no next/previous).
            if (session.isMediaNotificationController(controller)) {
                val playerCommands = base.availablePlayerCommands
                    .buildUpon()
                    .remove(Player.COMMAND_SEEK_TO_NEXT)
                    .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

                val sessionCommands = base.availableSessionCommands
                    .buildUpon()
                    .add(AudioCommands.ScrubStart)
                    .add(AudioCommands.ScrubStop)
                    .build()

                return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
            }

            // In-app UI controller: allow scrubbing commands too.
            return MediaSession.ConnectionResult.accept(
                base.availableSessionCommands.buildUpon()
                    .add(AudioCommands.ScrubStart)
                    .add(AudioCommands.ScrubStop)
                    .build(),
                base.availablePlayerCommands
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when {
                AudioCommands.isScrubStart(customCommand) -> {
                    player.setScrubbingModeEnabled(true)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                AudioCommands.isScrubStop(customCommand) -> {
                    player.setScrubbingModeEnabled(false)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> {
                    Log.w(TAG, "Unsupported custom command: ${customCommand.customAction}")
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
            }
        }
    }
}
package org.thoughtcrime.securesms.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.audio.model.AudioCommands
import org.thoughtcrime.securesms.audio.model.MediaItemFactory
import org.thoughtcrime.securesms.audio.model.MediaItemFactory.EXTRA_MESSAGE_ID
import org.thoughtcrime.securesms.audio.model.MediaItemFactory.EXTRA_THREAD_ADDRESS
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.util.getParcelableCompat

@AndroidEntryPoint
class AudioMediaService : MediaSessionService() {
    //todo AUDIO notification: add sender image in bg or track image if possible?
    //todo AUDIO notification: Noice notes: Title: Sender name Subtitle: "Voice note/message" --- Audio Title: [track name] Subtitle: [track artist]
    //todo AUDIO notification: open convo on tap (scroll to message?)
    //todo AUDIO notification: If I kill the app and swipe away the notification the audio continues - should maybe at least stop on app kill

    private val TAG = "AudioMediaService"

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Media-style notification + lockscreen controls handled by MediaSessionService.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .build()

        notificationProvider.setSmallIcon(R.drawable.ic_notification)

        setMediaNotificationProvider(
            notificationProvider
        )

        player = ExoPlayer.Builder(this).build().apply {
            addListener(servicePlayerListener)
        }

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()

        updateSessionActivityFromCurrentItem()

        // Apply policy for the initial item if any controller sets it very quickly.
        applyAudioPolicyForCurrentItem()
    }

    private fun updateSessionActivityFromCurrentItem() {
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return

        val thread = extras.getParcelableCompat<Address.Conversable>(EXTRA_THREAD_ADDRESS) ?: return

        val messageId = extras.getParcelableCompat<MessageId>(
            EXTRA_MESSAGE_ID
        ) ?: return

        val intent = ConversationActivityV2.createIntent(
            context = applicationContext,
            address = thread,
            scrollToMessage = messageId
        ).apply {
            // good defaults for "return to existing convo if open"
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        //todo AUDIO it looks like it's not scrolling to the message

        val requestCode = item.mediaId.hashCode()

        val pi = PendingIntent.getActivity(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        session?.setSessionActivity(pi)
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
            .setContentType(if (isVoice) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player.setAudioAttributes(attrs, /* handleAudioFocus = */ true)
    }

    private val servicePlayerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyAudioPolicyForCurrentItem()
            updateSessionActivityFromCurrentItem()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // stop service once track has ended
            if (playbackState == Player.STATE_ENDED) {
                player.stop()
                player.clearMediaItems()
                stopSelf()
                return
            }

            // If nothing queued, stop service
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
                    .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
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
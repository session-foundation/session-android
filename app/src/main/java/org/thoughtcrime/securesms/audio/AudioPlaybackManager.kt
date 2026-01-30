package org.thoughtcrime.securesms.audio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.decode.BitmapFactoryDecoder
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.audio.model.AudioCommands
import org.thoughtcrime.securesms.audio.model.AudioPlaybackState
import org.thoughtcrime.securesms.audio.model.MediaItemFactory
import org.thoughtcrime.securesms.audio.model.PlayableAudio
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.util.getParcelableCompat
import java.io.ByteArrayOutputStream
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "AudioPlaybackManager"

    private val _playbackState = MutableStateFlow<AudioPlaybackState>(AudioPlaybackState.Idle)
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var progressJob: Job? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var currentPlayable: PlayableAudio? = null
    private var isScrubbing: Boolean = false
    private var lastScrubSeekMs: Long = 0L


    fun play(playable: PlayableAudio, startPositionMs: Long = 0L) {
        ensureController { c ->
            val currentId = c.currentMediaItem?.mediaId
            if (currentId == playable.messageId.serialize()) {
                if (!c.isPlaying) c.play()
                return@ensureController
            }

            currentPlayable = playable
            _playbackState.value = AudioPlaybackState.Loading(playable)

            val item = MediaItemFactory.fromPlayable(playable)
            c.setMediaItem(item, startPositionMs)
            c.prepare()
            c.play()

            startProgressTracking()
            updateFromController()

            // Background: Resolve Metadata
            scope.launch(Dispatchers.IO) {
                // Pass the current metadata so we can compare and build upon it
                val betterMetadata = resolveMetadata(playable, item.mediaMetadata)

                if (betterMetadata != null) {
                    val newItem = MediaItemFactory.withMetadata(item, betterMetadata)
                    withContext(Dispatchers.Main) {
                        if (c.currentMediaItem?.mediaId == playable.messageId.serialize()) {
                            c.replaceMediaItem(c.currentMediaItemIndex, newItem)
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves metadata by checking the file first.
     * Returns NULL if no changes are needed to avoid unnecessary updates.
     */
    private suspend fun resolveMetadata(
        playable: PlayableAudio,
        currentMetadata: MediaMetadata
    ): MediaMetadata? {
        val finalTitle: String
        val finalArtist: String
        var artworkBytes: ByteArray? = null

        if (!playable.isVoiceNote) {
            var fileTitle: String? = null
            var fileArtist: String? = null
            var hasFileArt = false

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, playable.uri)
                fileTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                fileArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                hasFileArt = retriever.embeddedPicture != null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract ID3 tags", e)
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }

            // Title: ID3 -> Filename
            finalTitle = fileTitle?.takeIf { it.isNotBlank() } ?: playable.filename

            // Artist: ID3 -> Sender -> "Unknown"
            finalArtist = fileArtist?.takeIf { it.isNotBlank() }
                ?: playable.senderName
                        ?: context.getString(R.string.unknown)

            // Only load custom artwork if file has no embedded art
            if (!hasFileArt) {
                artworkBytes = loadAvatarOrLogo(playable, isVoiceNote = false)
            }

        } else {
            // Voice Note
            // Title: Sender -> Filename
            finalTitle = playable.senderName ?: playable.filename

            // Artist: "Voice Message"
            finalArtist = context.getString(R.string.messageVoice)

            // Always load artwork for voice notes
            artworkBytes = loadAvatarOrLogo(playable, isVoiceNote = true)
        }

        // Check for changes
        val changed = finalTitle != currentMetadata.title?.toString() ||
                finalArtist != currentMetadata.artist?.toString() ||
                !artworkBytes.contentEquals(currentMetadata.artworkData)

        if (!changed) return null

        // Build result
        return currentMetadata.buildUpon()
            .setTitle(finalTitle)
            .setDisplayTitle(finalTitle)
            .setArtist(finalArtist)
            .apply {
                if (artworkBytes != null) {
                    setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()
    }

    private suspend fun loadAvatarOrLogo(playable: PlayableAudio, isVoiceNote: Boolean): ByteArray? {
        var bitmap: Bitmap? = null

        // Try avatar
        if (playable.avatar != null) {
            bitmap = loadBitmapFromModel(playable.avatar, forceBitmapDecoder = true)
        }

        // Fallback to logo for voice notes only
        if (bitmap == null && isVoiceNote) {
            bitmap = loadBitmapFromModel(R.drawable.session_logo, forceBitmapDecoder = false)
        }

        return bitmap?.let {
            val stream = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray().takeIf { bytes -> bytes.size <= 500_000 }
        }
    }

    private suspend fun loadBitmapFromModel(model: Any, forceBitmapDecoder: Boolean): Bitmap? {
        val builder = ImageRequest.Builder(context)
            .data(model)
            .size(512, 512)
            .allowHardware(false)

        if (forceBitmapDecoder) {
            builder.decoderFactory(BitmapFactoryDecoder.Factory())
        }

        val result = context.imageLoader.execute(builder.build())
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun stop() {
        stopProgressTracking()
        controller?.stop()
        controller?.clearMediaItems()
        currentPlayable = null
        _playbackState.value = AudioPlaybackState.Idle
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        if (!isScrubbing) updateFromController()
    }

    fun setPlaybackSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        updateFromController()
    }

    fun cyclePlaybackSpeed() {
        val c = controller ?: return
        val current = c.playbackParameters.speed
        val next = when (current) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        c.setPlaybackSpeed(next)
        updateFromController()
    }

    fun isActive(messageId: MessageId): Boolean =
        controller?.currentMediaItem?.mediaId == messageId.serialize()

    fun beginScrub() {
        isScrubbing = true
        lastScrubSeekMs = 0L
        controller?.sendCustomCommand(AudioCommands.ScrubStart, Bundle.EMPTY)
    }

    fun scrubTo(positionMs: Long) {
        val c = controller ?: return

        // Throttle to ~80ms to reduce seek spam while still feeling live.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScrubSeekMs < 80) return
        lastScrubSeekMs = now

        c.seekTo(positionMs)
        // reflect UI immediately while finger is down
        updateFromController(scrubOverridePositionMs = positionMs)
    }

    fun endScrub(finalPositionMs: Long? = null) {
        val c = controller ?: return
        if (finalPositionMs != null) c.seekTo(finalPositionMs)

        isScrubbing = false
        controller?.sendCustomCommand(AudioCommands.ScrubStop, Bundle.EMPTY)
        updateFromController()
    }

    fun release() {
        stopProgressTracking()
        controller?.removeListener(controllerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        currentPlayable = null
        scope.cancel()
    }

    // Controller setup

    private fun ensureController(onReady: (MediaController) -> Unit) {
        controller?.let { onReady(it); return }

        val token = SessionToken(context, ComponentName(context, AudioMediaService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync().also { future ->
            future.addListener({
                try {
                    controller = future.get().also { it.addListener(controllerListener) }
                    onReady(controller!!)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to connect MediaController", e)
                }
            }, MoreExecutors.directExecutor())
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startProgressTracking() else stopProgressTracking()
            if (!isScrubbing) updateFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val ended = playbackState == Player.STATE_ENDED
            if (!isScrubbing) updateFromController(forceEnded = ended)
            if (ended) stopProgressTracking()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // If process was recreated, rebuild a minimal playable from the running MediaItem.
            if (currentPlayable == null) {
                currentPlayable = playableFromMediaItem(mediaItem)
            }
            if (!isScrubbing) updateFromController()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w(TAG, "ExoPlayer Error: ${error.errorCodeName}", error)
        }
    }

    // State mapping

    private fun startProgressTracking() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                if (!isScrubbing) updateFromController()
                delay(250)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updateFromController(
        forceEnded: Boolean = false,
        scrubOverridePositionMs: Long? = null
    ) {
        val c = controller ?: return
        val playable = currentPlayable ?: return

        val duration = c.duration.coerceAtLeast(0L).let { d ->
            // Use model duration if controller duration isn't known yet
            if (d <= 0 && playable.durationMs > 0) playable.durationMs else d
        }

        val position = scrubOverridePositionMs
            ?: if (forceEnded && duration > 0) duration else c.currentPosition.coerceAtLeast(0L)

        val buffered = c.bufferedPosition.coerceAtLeast(0L)
        val speed = c.playbackParameters.speed
        val buffering = c.playbackState == Player.STATE_BUFFERING

        // Fix: Detect IDLE state to avoid "Loading" trap
        val isIdle = c.playbackState == Player.STATE_IDLE

        _playbackState.value = when {
            // Case 1: Actually Playing
            c.isPlaying ->
                AudioPlaybackState.Playing(
                    playable = playable,
                    positionMs = position,
                    durationMs = duration,
                    bufferedPositionMs = buffered,
                    playbackSpeed = speed,
                    isBuffering = buffering
                )

            // Case 2: Buffering and playing, or explicitly commanded to play but not ready
            buffering && c.playWhenReady -> AudioPlaybackState.Playing(
                playable = playable,
                positionMs = position,
                durationMs = duration,
                bufferedPositionMs = buffered,
                playbackSpeed = speed,
                isBuffering = true
            )

            // Case 3: Paused / Ready / Ended
            c.playbackState == Player.STATE_READY || forceEnded ->
                AudioPlaybackState.Paused(
                    playable = playable,
                    positionMs = position,
                    durationMs = duration,
                    bufferedPositionMs = buffered,
                    playbackSpeed = speed,
                    isBuffering = buffering
                )

            // Case 4: Idle (Error or Stopped) -> Reset to Idle or Paused
            isIdle -> {
                // If we have a playable but we are IDLE, we probably failed or stopped.
                // Treat as Paused (start over) or Idle.
                AudioPlaybackState.Paused(
                    playable = playable,
                    positionMs = 0,
                    durationMs = duration,
                    bufferedPositionMs = 0,
                    playbackSpeed = 1f,
                    isBuffering = false
                )
            }

            // Case 5: Actual Loading
            else -> AudioPlaybackState.Loading(playable)
        }
    }

    // Restoration helpers (no extra “contract” beyond MediaId + MediaMetadata)

    private fun playableFromMediaItem(item: MediaItem?): PlayableAudio? {
        if (item == null) return null

        val extras = item.mediaMetadata.extras ?: return null

        val thread: Address.Conversable = extras.getParcelableCompat<Address.Conversable>(
            MediaItemFactory.EXTRA_THREAD_ADDRESS
        ) ?: return null

        val messageId: MessageId = extras.getParcelableCompat<MessageId>(
            MediaItemFactory.EXTRA_MESSAGE_ID
        ) ?: return null

        val uri: Uri = item.localConfiguration?.uri
            ?: return null

        return PlayableAudio(
            messageId = messageId,
            uri = uri,
            thread = thread,
            isVoiceNote = extras.getBoolean(MediaItemFactory.EXTRA_IS_VOICE, false),
            durationMs = extras.getLong(MediaItemFactory.EXTRA_DURATION_HINT, -1L),
            senderName = extras.getString(MediaItemFactory.EXTRA_SENDER_NAME),
            filename = extras.getString(MediaItemFactory.EXTRA_FILENAME, "")
        )
    }
}
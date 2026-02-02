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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import java.util.concurrent.ConcurrentHashMap
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

    // Cache to remember each audio's basic state
    data class SavedAudioState(
        val positionMs: Long = 0L,
        val playbackSpeed: Float = 1f
    )

    private var pendingMediaId: String? = null
    private var pendingStartPositionMs: Long = 0L

    private val playbackCache = ConcurrentHashMap<String, SavedAudioState>()

    fun getSavedState(messageId: MessageId): SavedAudioState {
        return playbackCache[messageId.serialize()] ?: SavedAudioState()
    }

    fun play(playable: PlayableAudio, startPositionMs: Long = -1L) {
        ensureController { c ->
            val currentId = c.currentMediaItem?.mediaId

            // Changing tracks
            if (currentId != playable.messageId.serialize()) {

                // Save the state of the previous track before swapping currentPlayable
                // If we don't do this here, the next updateFromController will attribute
                // the old track's position to the new track's ID, or reset the old one.
                saveCurrentStateToCache()

                val savedState = getSavedState(playable.messageId)
                val finalPosition = if (startPositionMs >= 0) startPositionMs else savedState.positionMs

                currentPlayable = playable
                _playbackState.value = AudioPlaybackState.Loading(playable)

                val item = MediaItemFactory.fromPlayable(playable)

                // Record transition expectation BEFORE setMediaItem
                pendingMediaId = item.mediaId
                pendingStartPositionMs = finalPosition

                c.setMediaItem(item, finalPosition)

                if (savedState.playbackSpeed != c.playbackParameters.speed) {
                    c.setPlaybackSpeed(savedState.playbackSpeed)
                }

                c.prepare()

                // Resolve richer metadata in background
                scope.launch(Dispatchers.IO) {
                    val betterMetadata = resolveMetadata(playable, item.mediaMetadata)
                    if (betterMetadata != null) {
                        val newItem = MediaItemFactory.withMetadata(item, betterMetadata)

                        withContext(Dispatchers.Main) {
                            // Only replace if this item is still current
                            if (controller?.currentMediaItem?.mediaId == item.mediaId) {
                                controller?.replaceMediaItem(
                                    controller!!.currentMediaItemIndex,
                                    newItem
                                )
                            }
                        }
                    }
                }
            } else {
                if (startPositionMs >= 0) c.seekTo(startPositionMs)
            }

            if (!c.isPlaying) c.play()
            startProgressTracking()
        }
    }

    private fun updateFromController(forceEnded: Boolean = false, scrubOverridePositionMs: Long? = null) {
        val c = controller ?: return
        val playable = currentPlayable ?: return

        if (c.currentMediaItem?.mediaId != playable.messageId.serialize()) return

        val duration = c.duration.coerceAtLeast(0L).let { d ->
            if (d <= 0 && playable.durationMs > 0) playable.durationMs else d
        }

        val playbackState = c.playbackState
        val rawPosition = c.currentPosition.coerceAtLeast(0L)

        // If we're mid-transition onto this item, clamp/override position until READY.
        val isPendingThisItem = pendingMediaId == playable.messageId.serialize()
        val position = when {
            scrubOverridePositionMs != null -> scrubOverridePositionMs
            forceEnded -> 0L
            isPendingThisItem && playbackState != Player.STATE_READY -> pendingStartPositionMs
            else -> rawPosition
        }

        // Clear pending once we are READY for that item (first stable state)
        if (isPendingThisItem && playbackState == Player.STATE_READY) {
            pendingMediaId = null
        }

        val speed = c.playbackParameters.speed
        val buffered = c.bufferedPosition.coerceAtLeast(0L)
        val buffering = playbackState == Player.STATE_BUFFERING
        val isIdle = playbackState == Player.STATE_IDLE

        // Only cache when stable AND not pending
        val isStable = (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) && !isPendingThisItem
        if (isStable) {
            playbackCache[playable.messageId.serialize()] =
                if (forceEnded) SavedAudioState(0L, speed) else SavedAudioState(position, speed)
        }

        _playbackState.value = when {
            c.isPlaying -> AudioPlaybackState.Playing(
                playable,
                position,
                duration,
                buffered,
                speed,
                buffering
            )

            buffering && c.playWhenReady -> AudioPlaybackState.Playing(
                playable,
                position,
                duration,
                buffered,
                speed,
                true
            )

            playbackState == Player.STATE_READY || forceEnded -> AudioPlaybackState.Paused(
                playable,
                position,
                duration,
                buffered,
                speed,
                buffering
            )

            isIdle -> {
                // when going idle the player resets the speed to 1 so we need to use the cached value, if any
                val cachedSpeed = playbackCache[playable.messageId.serialize()]?.playbackSpeed ?: speed

                AudioPlaybackState.Paused(
                    playable,
                    position,
                    duration,
                    buffered,
                    cachedSpeed,
                    false
                )
            }

            else -> AudioPlaybackState.Loading(playable)
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
        val artworkChanged =
            when {
                artworkBytes == null && currentMetadata.artworkData == null -> false
                artworkBytes == null || currentMetadata.artworkData == null -> true
                else -> !artworkBytes.contentEquals(currentMetadata.artworkData)
            }

        val changed =
            finalTitle != currentMetadata.title?.toString() ||
                    finalArtist != currentMetadata.artist?.toString() ||
                    artworkChanged

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

    /**
     * Helper to snapshot the current player state into the cache.
     * Safe to call at any time.
     */
    private fun saveCurrentStateToCache() {
        val c = controller ?: return
        val p = currentPlayable ?: return

        val position = c.currentPosition.coerceAtLeast(0L)
        val speed = c.playbackParameters.speed

        playbackCache[p.messageId.serialize()] = SavedAudioState(position, speed)
    }

    /**
     * Returns a Flow specific to this audio (message).
     */
    fun observeMessageState(playable: PlayableAudio): Flow<AudioPlaybackState> {
        return _playbackState
            .map { globalState ->
                val isGlobalActive = when (globalState) {
                    is AudioPlaybackState.Playing -> globalState.playable.messageId == playable.messageId
                    is AudioPlaybackState.Paused  -> globalState.playable.messageId == playable.messageId
                    is AudioPlaybackState.Loading -> globalState.playable.messageId == playable.messageId
                    else -> false
                }

                if (isGlobalActive) {
                    globalState
                } else {
                    val saved = getSavedState(playable.messageId)
                    if (saved.positionMs > 0) {
                        AudioPlaybackState.Paused(
                            playable = playable,
                            positionMs = saved.positionMs,
                            durationMs = playable.durationMs,
                            bufferedPositionMs = 0,
                            playbackSpeed = saved.playbackSpeed,
                            isBuffering = false
                        )
                    } else {
                        AudioPlaybackState.Idle
                    }
                }
            }
            .distinctUntilChanged()
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
            2f -> 0.5f
            else -> 1f
        }

        setPlaybackSpeed(next)
    }

    fun isActive(messageId: MessageId): Boolean =
        controller?.currentMediaItem?.mediaId == messageId.serialize()

    fun beginScrub() {
        isScrubbing = true
        controller?.sendCustomCommand(AudioCommands.ScrubStart, Bundle.EMPTY)
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
            while (isActive && controller?.isPlaying == true) {
                if (!isScrubbing) updateFromController()
                delay(500)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

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
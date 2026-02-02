package org.thoughtcrime.securesms.audio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
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
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.audio.model.AudioCommands
import org.thoughtcrime.securesms.audio.model.AudioPlaybackState
import org.thoughtcrime.securesms.audio.model.MediaItemFactory
import org.thoughtcrime.securesms.audio.model.PlayableAudio
import org.thoughtcrime.securesms.database.model.MessageId
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "AudioPlaybackManager"

    private val _playbackState =
        MutableStateFlow<AudioPlaybackState>(AudioPlaybackState.Idle)
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var progressJob: Job? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var currentPlayable: PlayableAudio? = null
    private var isScrubbing = false

    // Per-message intent cache
    data class SavedAudioState(
        val positionMs: Long = 0L,
        val playbackSpeed: Float = 1f
    )

    //todo AUDIO should we clear the cache when leaving a conversation?
    private val playbackCache = ConcurrentHashMap<String, SavedAudioState>()

    fun getSavedState(messageId: MessageId): SavedAudioState =
        playbackCache[messageId.serialize()] ?: SavedAudioState()

    fun play(playable: PlayableAudio, startPositionMs: Long = -1L) {
        ensureController { c ->
            val currentId = c.currentMediaItem?.mediaId
            val newId = playable.messageId.serialize()

            if (currentId != newId) {
                saveCurrentStateToCache()

                val saved = getSavedState(playable.messageId)
                val startPos =
                    if (startPositionMs >= 0) startPositionMs else saved.positionMs

                currentPlayable = playable

                _playbackState.value = AudioPlaybackState.Active.Loading(
                    playable = playable,
                    playbackSpeed = saved.playbackSpeed
                )

                val item = MediaItemFactory.fromPlayable(playable)

                c.setMediaItem(item, startPos)
                c.setPlaybackSpeed(saved.playbackSpeed)
                c.prepare()

                // Resolve metadata asynchronously
                scope.launch(Dispatchers.IO) {
                    val better = resolveMetadata(playable, item.mediaMetadata)
                    if (better != null) {
                        val updated = MediaItemFactory.withMetadata(item, better)
                        withContext(Dispatchers.Main) {
                            if (controller?.currentMediaItem?.mediaId == item.mediaId) {
                                controller?.replaceMediaItem(
                                    controller!!.currentMediaItemIndex,
                                    updated
                                )
                            }
                        }
                    }
                }
            } else if (startPositionMs >= 0) {
                c.seekTo(startPositionMs)
            }

            if (!c.isPlaying) c.play()
            startProgressTracking()
        }
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
        val p = currentPlayable ?: return
        controller?.setPlaybackSpeed(speed)

        val pos = controller?.currentPosition ?: 0L
        playbackCache[p.messageId.serialize()] =
            SavedAudioState(pos, speed)

        updateFromController()
    }

    fun cyclePlaybackSpeed() {
        val current = controller?.playbackParameters?.speed ?: 1f
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

    fun endScrub(finalPositionMs: Long) {
        controller?.seekTo(finalPositionMs)
        isScrubbing = false
        controller?.sendCustomCommand(AudioCommands.ScrubStop, Bundle.EMPTY)
        updateFromController()
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

    private fun updateFromController(forceEnded: Boolean = false) {
        val c = controller ?: return
        val playable = currentPlayable ?: return
        if (c.currentMediaItem?.mediaId != playable.messageId.serialize()) return

        val duration =
            c.duration.takeIf { it > 0 } ?: playable.durationMs.coerceAtLeast(0)

        val position =
            if (forceEnded) 0L else c.currentPosition.coerceAtLeast(0L)

        val buffered = c.bufferedPosition.coerceAtLeast(0L)
        val buffering = c.playbackState == Player.STATE_BUFFERING

        val cached =
            playbackCache[playable.messageId.serialize()] ?: SavedAudioState()

        // Persist resume intent when stable
        if (c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_ENDED) {
            playbackCache[playable.messageId.serialize()] =
                if (forceEnded) {
                    SavedAudioState(0L, cached.playbackSpeed)
                } else {
                    SavedAudioState(position, cached.playbackSpeed)
                }
        }

        _playbackState.value = when {
            c.isPlaying -> AudioPlaybackState.Active.Playing(
                playable,
                position,
                duration,
                buffered,
                cached.playbackSpeed,
                buffering
            )

            c.playbackState == Player.STATE_READY || forceEnded -> AudioPlaybackState.Active.Paused(
                playable,
                position,
                duration,
                buffered,
                cached.playbackSpeed,
                buffering
            )

            else -> AudioPlaybackState.Active.Loading(
                playable,
                cached.playbackSpeed
            )
        }
    }

    fun observeMessageState(playable: PlayableAudio): Flow<AudioPlaybackState> {
        return playbackState
            .map { state ->
                val isActive = when (state) {
                    is AudioPlaybackState.Active.Playing -> state.playable.messageId == playable.messageId
                    is AudioPlaybackState.Active.Paused -> state.playable.messageId == playable.messageId
                    is AudioPlaybackState.Active.Loading -> state.playable.messageId == playable.messageId
                    else -> false
                }

                if (isActive) {
                    state
                } else {
                    val saved = getSavedState(playable.messageId)
                    if (saved.positionMs > 0) {
                        AudioPlaybackState.Active.Paused(
                            playable,
                            saved.positionMs,
                            playable.durationMs,
                            0,
                            saved.playbackSpeed,
                            false
                        )
                    } else {
                        AudioPlaybackState.Idle
                    }
                }
            }
            .distinctUntilChanged()
    }

    private fun ensureController(onReady: (MediaController) -> Unit) {
        controller?.let { onReady(it); return }

        val token = SessionToken(
            context,
            ComponentName(context, AudioMediaService::class.java)
        )

        controllerFuture = MediaController.Builder(context, token)
            .buildAsync()
            .also { future ->
                future.addListener({
                    try {
                        controller = future.get().also {
                            it.addListener(controllerListener)
                        }
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

        override fun onPlaybackStateChanged(state: Int) {
            val ended = state == Player.STATE_ENDED
            if (!isScrubbing) updateFromController(forceEnded = ended)
            if (ended) stopProgressTracking()
        }
    }

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

    private fun saveCurrentStateToCache() {
        val c = controller ?: return
        val p = currentPlayable ?: return
        val cached = playbackCache[p.messageId.serialize()] ?: SavedAudioState()

        playbackCache[p.messageId.serialize()] =
            SavedAudioState(c.currentPosition.coerceAtLeast(0L), cached.playbackSpeed)
    }
}

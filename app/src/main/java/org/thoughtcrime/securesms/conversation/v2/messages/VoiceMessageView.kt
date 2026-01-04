package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.RelativeLayout
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewVoiceMessageBinding
import org.thoughtcrime.securesms.audio.AudioPlaybackManager
import org.thoughtcrime.securesms.audio.model.AudioPlaybackState
import org.thoughtcrime.securesms.audio.model.PlayableAudio
import org.thoughtcrime.securesms.components.CornerMask
import org.thoughtcrime.securesms.conversation.v2.utilities.MessageBubbleUtilities
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.util.MediaUtil
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class VoiceMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {

    @Inject lateinit var audioPlaybackManager: AudioPlaybackManager

    private val binding by lazy { ViewVoiceMessageBinding.bind(this) }
    private val cornerMask by lazy { CornerMask(this) }

    var delegate: VisibleMessageViewDelegate? = null
    var indexInAdapter = -1

    private var playable: PlayableAudio? = null

    // View-owned coroutine for collecting state
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var collectJob: Job? = null

    private var durationMs: Long = 0L
    private var progress: Float = 0f
    private var isPlaying: Boolean = false

    fun bind(
        message: MmsMessageRecord,
        playable: PlayableAudio?,
        isStartOfMessageCluster: Boolean,
        isEndOfMessageCluster: Boolean
    ) {
        this.playable = playable

        // existing corner mask logic unchanged
        val audioSlide = message.slideDeck.audioSlide!!
        binding.voiceMessageViewLoader.isVisible = audioSlide.isInProgress
        val radii = MessageBubbleUtilities.calculateRadii(context, isStartOfMessageCluster, isEndOfMessageCluster, message.isOutgoing)
        cornerMask.setTopLeftRadius(radii[0])
        cornerMask.setTopRightRadius(radii[1])
        cornerMask.setBottomRightRadius(radii[2])
        cornerMask.setBottomLeftRadius(radii[3])

        // initial duration display
        durationMs = playable?.durationMs?.takeIf { it > 0 } ?: 0L
        binding.voiceMessageViewDurationTextView.text =
            if (durationMs > 0) MediaUtil.getFormattedVoiceMessageDuration(durationMs)
            else "--:--"

        // Start observing global playback state
        startCollectingPlaybackState()

        // Render immediately (in case already playing when view binds)
        renderFrom(audioPlaybackManager.playbackState.value)
    }

    fun recycle() {
        collectJob?.cancel()
        collectJob = null
        playable = null
        scope.coroutineContext.cancelChildren()
    }

    fun onPlayPauseClicked() {
        val p = playable ?: return

        // If this row is the active item, just toggle
        if (audioPlaybackManager.isActive(p.messageId)) {
            audioPlaybackManager.togglePlayPause()
        } else {
            audioPlaybackManager.play(p)
        }
    }

    fun onSpeedToggleClicked() {
        val p = playable ?: return
        if (audioPlaybackManager.isActive(p.messageId)) {
            audioPlaybackManager.cyclePlaybackSpeed()
        }
    }

    private fun startCollectingPlaybackState() {
        collectJob?.cancel()
        collectJob = scope.launch {
            audioPlaybackManager.playbackState.collect { state ->
                renderFrom(state)
            }
        }
    }

    private fun renderFrom(state: AudioPlaybackState) {
        val p = playable ?: return

        val isActive = when (state) {
            is AudioPlaybackState.Loading -> state.playable.messageId == p.messageId
            is AudioPlaybackState.Playing -> state.playable.messageId == p.messageId
            is AudioPlaybackState.Paused  -> state.playable.messageId == p.messageId
            else -> false
        }

        if (!isActive) {
            // Not this row → reset to initial appearance
            isPlaying = false
            progress = 0f
            renderIcon()
            renderProgress(0f)
            // show full duration (or hint)
            val dur = p.durationMs.takeIf { it > 0 } ?: durationMs
            binding.voiceMessageViewDurationTextView.text =
                if (dur > 0) MediaUtil.getFormattedVoiceMessageDuration(dur) else "--:--"
            return
        }

        when (state) {
            is AudioPlaybackState.Loading -> {
                binding.voiceMessageViewLoader.isVisible = true
                isPlaying = false
                renderIcon()
            }
            is AudioPlaybackState.Playing -> {
                binding.voiceMessageViewLoader.isVisible = state.isBuffering
                isPlaying = true
                durationMs = state.durationMs
                progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                renderIcon()
                renderProgress(progress)
                val remaining = (state.durationMs - state.positionMs).coerceAtLeast(0L)
                binding.voiceMessageViewDurationTextView.text = MediaUtil.getFormattedVoiceMessageDuration(remaining)
            }
            is AudioPlaybackState.Paused -> {
                binding.voiceMessageViewLoader.isVisible = state.isBuffering
                isPlaying = false
                durationMs = state.durationMs
                progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                renderIcon()
                renderProgress(progress)
                val remaining = (state.durationMs - state.positionMs).coerceAtLeast(0L)
                binding.voiceMessageViewDurationTextView.text = MediaUtil.getFormattedVoiceMessageDuration(remaining)
            }
            else -> Unit
        }
    }

    private fun renderProgress(p: Float) {
        val layoutParams = binding.progressView.layoutParams as RelativeLayout.LayoutParams
        layoutParams.width = (width.toFloat() * p).roundToInt()
        binding.progressView.layoutParams = layoutParams
    }

    private fun renderIcon() {
        val iconID = if (isPlaying) R.drawable.exo_icon_pause else R.drawable.exo_icon_play
        binding.voiceMessagePlaybackImageView.setImageResource(iconID)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        cornerMask.mask(canvas)
    }
}

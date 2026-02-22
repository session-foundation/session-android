package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.widget.RelativeLayout
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewVoiceMessageBinding
import org.session.libsession.utilities.getColorFromAttr
import org.thoughtcrime.securesms.audio.AudioPlaybackManager
import org.thoughtcrime.securesms.audio.model.AudioPlaybackState
import org.thoughtcrime.securesms.audio.model.PlayableAudio
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.setSafeOnClickListener
import javax.inject.Inject

@AndroidEntryPoint
class VoiceMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {
    @Inject lateinit var audioPlaybackManager: AudioPlaybackManager

    private val binding by lazy { ViewVoiceMessageBinding.bind(this) }

    var delegate: VisibleMessageViewDelegate? = null

    private var playable: PlayableAudio? = null

    // View-owned coroutine for collecting state
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var collectJob: Job? = null

    private var durationMs: Long = 0L
    private var isPlaying: Boolean = false

    // Prevents UI jitter while the user is dragging the thumb
    private var isUserScrubbing = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        setupListeners()
    }

    private fun setupListeners() {
        // Slider listener
        binding.voiceMessageSeekBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.voiceMessageViewDurationTextView.text =
                    MediaUtil.getFormattedVoiceMessageDuration(value.toLong())
            }
        }

        binding.voiceMessageSeekBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                val p = playable ?: return
                isUserScrubbing = true
                if (audioPlaybackManager.isActive(p.messageId)) {
                    audioPlaybackManager.beginScrub()
                }
            }

            override fun onStopTrackingTouch(slider: Slider) {
                val p = playable ?: return
                val pos = slider.value.toLong()
                isUserScrubbing = false

                if (audioPlaybackManager.isActive(p.messageId)) {
                    audioPlaybackManager.endScrub(pos)
                } else {
                    audioPlaybackManager.play(p, startPositionMs = pos)
                }
            }
        })

        // Speed Button Listener
        binding.voiceMessageSpeedButton.setSafeOnClickListener {
            onSpeedToggleClicked()
        }

        // play pause
        binding.playPauseContainer.setSafeOnClickListener {
            onPlayPauseClicked()
        }
    }

    fun bind(
        playable: PlayableAudio?,
        message: MessageRecord
    ) {
        this.playable = playable

        val textColor = VisibleMessageContentView.getTextColor(context, message)

        val (color1, color2, trackEmptyColor) = if (message.isOutgoing) {
            intArrayOf(
                context.getColorFromAttr(R.attr.backgroundSecondary),  // bg secondary
                context.getColorFromAttr(android.R.attr.textColorPrimary), // text primary
                ColorUtils.setAlphaComponent(context.getColorFromAttr(R.attr.backgroundSecondary), 128)
            )
        } else {
            intArrayOf(
                context.getColorFromAttr(R.attr.accentColor),  // accent
                context.getColorFromAttr(R.attr.colorPrimary), // bg primary
                context.getColorFromAttr(android.R.attr.textColorSecondary), // text secondary
            )
        }

        // Apply Colors
        binding.voiceMessageViewDurationTextView.setTextColor(textColor)
        binding.audioTitle.setTextColor(textColor)

        binding.playBg.backgroundTintList = ColorStateList.valueOf(color1)

        binding.voiceMessagePlaybackImageView.imageTintList = ColorStateList.valueOf(color2)
        binding.voiceMessageViewLoader.backgroundTintList = ColorStateList.valueOf(color2)

        // Apply Colors to Slider
        val sliderColorList = ColorStateList.valueOf(color1)
        binding.voiceMessageSeekBar.thumbTintList = sliderColorList
        binding.voiceMessageSeekBar.trackActiveTintList = sliderColorList
        binding.voiceMessageSeekBar.trackInactiveTintList = ColorStateList.valueOf(trackEmptyColor)

        if (message.isOutgoing) {
            binding.voiceMessageSpeedButton.backgroundTintList = ColorStateList.valueOf(color1)
            binding.voiceMessageSpeedButton.setTextColor(color2)
        } else {
            binding.voiceMessageSpeedButton.backgroundTintList = ColorStateList.valueOf(color2)
            binding.voiceMessageSpeedButton.setTextColor(textColor)
        }

        // duration
        this.durationMs = playable?.durationMs?.coerceAtLeast(0) ?: 0L
        binding.voiceMessageViewDurationTextView.text =
            MediaUtil.getFormattedVoiceMessageDuration(this.durationMs)

        // title
        binding.audioTitle.text = if(playable?.isVoiceNote == true) context.getString(R.string.messageVoice)
        else playable?.filename ?: context.getString(R.string.unknown)

        // Observe state from audio manager
        startCollectingPlaybackState()
    }

    fun recycle() {
        collectJob?.cancel()
        collectJob = null
        playable = null
        scope.coroutineContext.cancelChildren()
        isUserScrubbing = false
    }

    fun onPlayPauseClicked() {
        val p = playable ?: return
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
        val p = playable ?: return
        collectJob?.cancel()

        collectJob = scope.launch {
            // observe data for THIS audio/message
            audioPlaybackManager.observeMessageState(p).collect { state ->
                render(state)
            }
        }
    }

    private fun render(state: AudioPlaybackState) {
        binding.voiceMessageSpeedButton.text = state.playbackSpeedFormatted()

        when (state) {
            is AudioPlaybackState.Idle -> {
                isPlaying = false
                binding.voiceMessageViewLoader.isVisible = false
                updateSeekBar(0, this.durationMs) // Reset
                renderIcon()
            }
            is AudioPlaybackState.Active.Loading -> {
                isPlaying = false
                binding.voiceMessageViewLoader.isVisible = true
                renderIcon()
            }
            is AudioPlaybackState.Active.Playing -> {
                isPlaying = true
                binding.voiceMessageViewLoader.isVisible = state.isBuffering
                updateSeekBar(state.positionMs, state.durationMs)
                renderIcon()
            }
            is AudioPlaybackState.Active.Paused -> {
                isPlaying = false
                binding.voiceMessageViewLoader.isVisible = state.isBuffering
                updateSeekBar(state.positionMs, state.durationMs)
                renderIcon()
            }
            is AudioPlaybackState.Active.Error -> {
                isPlaying = false
                binding.voiceMessageViewLoader.isVisible = false
                renderIcon()
            }
        }
    }

    private fun updateSeekBar(positionMs: Long, durationMs: Long) {
        val safeDuration = if (durationMs > 0) durationMs.toFloat() else this.durationMs.toFloat()

        // Slider crashes if valueTo <= valueFrom
        val finalValueTo = if (safeDuration > 0f) safeDuration else 1f

        if (binding.voiceMessageSeekBar.valueTo != finalValueTo) {
            binding.voiceMessageSeekBar.valueTo = finalValueTo
        }

        if (!isUserScrubbing) {
            // Ensure position is within [0, valueTo] to avoid crashes
            val safePos = positionMs.toFloat().coerceIn(0f, finalValueTo)
            binding.voiceMessageSeekBar.value = safePos

            val remaining = (finalValueTo - safePos).toLong().coerceAtLeast(0L)
            binding.voiceMessageViewDurationTextView.text =
                MediaUtil.getFormattedVoiceMessageDuration(remaining)
        }
    }

    private fun renderIcon() {
        val iconID = if (isPlaying) R.drawable.pause else R.drawable.play
        binding.voiceMessagePlaybackImageView.setImageResource(iconID)
    }
}
package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.widget.RelativeLayout
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
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

    //todo AUDIO need to add the auto playback of consecutive audio messages
    //todo AUDIO can I retain the UI as the mini player slides up?
    //todo AUDIO need to counter the height of the mini player
    //todo AUDIO add scroll to message on mini player click

    @Inject lateinit var audioPlaybackManager: AudioPlaybackManager

    private val binding by lazy { ViewVoiceMessageBinding.bind(this) }

    var delegate: VisibleMessageViewDelegate? = null
    var indexInAdapter = -1

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
        // Seek Bar Listener
        binding.voiceMessageSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Update the timer text immediately while dragging for visual feedback
                    binding.voiceMessageViewDurationTextView.text =
                        MediaUtil.getFormattedVoiceMessageDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                val p = playable ?: return
                isUserScrubbing = true

                if (audioPlaybackManager.isActive(p.messageId)) {
                    audioPlaybackManager.beginScrub()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val p = playable ?: return
                val pos = seekBar?.progress?.toLong() ?: return
                isUserScrubbing = false

                if (audioPlaybackManager.isActive(p.messageId)) {
                    audioPlaybackManager.endScrub(pos)
                } else {
                    // Scrubbing = intent to play from here
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

        val mainColor = VisibleMessageContentView.getTextColor(context, message)
        val buttonBgColor = if(message.isOutgoing) context.getColorFromAttr(R.attr.colorPrimary) else mainColor
        val buttonActionColor = if(message.isOutgoing) Color.WHITE else Color.BLACK


        // Apply Colors
        binding.voiceMessageViewDurationTextView.setTextColor(mainColor)
        binding.voiceMessageSpeedButton.setTextColor(mainColor)
        binding.audioTitle.setTextColor(mainColor)
        binding.voiceMessageSpeedButton.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(buttonBgColor, 30))

        binding.voiceMessageViewLoader.backgroundTintList = ColorStateList.valueOf(buttonActionColor)

        // Tint the SeekBar
        val tintList = ColorStateList.valueOf(buttonBgColor)
        binding.voiceMessageSeekBar.thumbTintList = tintList
        binding.voiceMessageSeekBar.progressTintList = tintList
        // Make the track slightly transparent
        binding.voiceMessageSeekBar.progressBackgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(mainColor, 70))

        // Tint Play button background/icon
        binding.playBg.backgroundTintList = ColorStateList.valueOf(buttonBgColor)
        binding.voiceMessagePlaybackImageView.imageTintList = ColorStateList.valueOf(buttonActionColor)

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
                updateSeekBar(0, 0) // Reset
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
        // Handle Unknown/Zero duration gracefully
        val safeDuration = if (durationMs > 0) durationMs else this.durationMs

        if (binding.voiceMessageSeekBar.max != safeDuration.toInt()) {
            binding.voiceMessageSeekBar.max = safeDuration.toInt()
        }

        if (!isUserScrubbing) {
            binding.voiceMessageSeekBar.progress = positionMs.toInt()
            val remaining = (safeDuration - positionMs).coerceAtLeast(0L)
            binding.voiceMessageViewDurationTextView.text =
                MediaUtil.getFormattedVoiceMessageDuration(remaining)
        }
    }

    private fun renderIcon() {
        val iconID = if (isPlaying) R.drawable.media3_icon_pause else R.drawable.media3_icon_play
        binding.voiceMessagePlaybackImageView.setImageResource(iconID)
    }
}
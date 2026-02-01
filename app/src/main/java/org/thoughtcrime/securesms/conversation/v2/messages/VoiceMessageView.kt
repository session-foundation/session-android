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

    //todo AUDIO rework loader
    //todo AUDIO clicking other message's scrubber messes with currently playing ( different) message
    //todo AUDIO press ripple of thumb isn't great on outgoing
    //todo AUDIO playback speed is shared by all views... Should we hide button when not playing?
    //todo AUDIO state is shared across all audio view > shouldn't reflect one message's scrub and playback speed across all audio messages
    //todo AUDIO

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
                isUserScrubbing = true
                // Tell manager to stop auto-updates
                audioPlaybackManager.beginScrub()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserScrubbing = false
                seekBar?.let {
                    // Commit the seek
                    audioPlaybackManager.endScrub(it.progress.toLong())
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
        binding.voiceMessageSpeedButton.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(buttonBgColor, 30))

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

        // Initial duration display
        durationMs = playable?.durationMs?.takeIf { it > 0 } ?: 0L

        // Initialize SeekBar max
        binding.voiceMessageSeekBar.max = durationMs.toInt()
        binding.voiceMessageSeekBar.progress = 0

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
        // Reset UI
        binding.voiceMessageSeekBar.progress = 0
        binding.voiceMessageSpeedButton.text = AudioPlaybackState.DEFAULT_PLAYBACK_SPEED_DISPLAY
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

        // Update Speed Text
        binding.voiceMessageSpeedButton.text = state.playbackSpeedFormatted()

        // Handle Inactive State
        if (!isActive) {
            isPlaying = false
            binding.voiceMessageViewLoader.isVisible = false
            binding.voiceMessageSeekBar.progress = 0
            renderIcon()

            val dur = p.durationMs.takeIf { it > 0 } ?: durationMs
            binding.voiceMessageViewDurationTextView.text =
                if (dur > 0) MediaUtil.getFormattedVoiceMessageDuration(dur) else "--:--"
            return
        }

        // Handle Active State
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

                updateSeekBar(state.positionMs, state.durationMs)
                renderIcon()
            }
            is AudioPlaybackState.Paused -> {
                binding.voiceMessageViewLoader.isVisible = state.isBuffering
                isPlaying = false
                durationMs = state.durationMs

                updateSeekBar(state.positionMs, state.durationMs)
                renderIcon()
            }
            else -> Unit
        }
    }

    private fun updateSeekBar(positionMs: Long, durationMs: Long) {
        // Ensure max is correct
        if (binding.voiceMessageSeekBar.max != durationMs.toInt()) {
            binding.voiceMessageSeekBar.max = durationMs.toInt()
        }

        // Only update progress if the user is not dragging it
        if (!isUserScrubbing) {
            binding.voiceMessageSeekBar.progress = positionMs.toInt()

            // Show remaining time
            val remaining = (durationMs - positionMs).coerceAtLeast(0L)
            binding.voiceMessageViewDurationTextView.text = MediaUtil.getFormattedVoiceMessageDuration(remaining)
        }
    }

    private fun renderIcon() {
        val iconID = if (isPlaying) R.drawable.exo_icon_pause else R.drawable.exo_icon_play
        binding.voiceMessagePlaybackImageView.setImageResource(iconID)
    }
}
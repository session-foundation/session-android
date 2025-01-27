package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.RelativeLayout
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewVoiceMessageBinding
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.audio.AudioSlidePlayer
import org.thoughtcrime.securesms.components.CornerMask
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.utilities.MessageBubbleUtilities
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@AndroidEntryPoint
class VoiceMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr), AudioSlidePlayer.Listener {
    private val TAG = "VoiceMessageView"

    private val conversationActivityV2: ConversationActivityV2? =
        (context as? ConversationActivityV2)

    init {
        if (conversationActivityV2 == null) {
            Log.e(TAG, "VoiceMessageView must be used in ConversationActivityV2")
        }
    }
    @Inject lateinit var attachmentDb: AttachmentDatabase

    private val binding: ViewVoiceMessageBinding by lazy { ViewVoiceMessageBinding.bind(this) }
    private val cornerMask by lazy { CornerMask(this) }

    private var isPlaying = false
        set(value) {
            field = value
            renderIcon()
        }

    private var progress = 0.0
    private var durationMS = 0L
    private var player: AudioSlidePlayer? = null
    var delegate: VisibleMessageViewDelegate? = null
    var indexInAdapter = -1

    // region Updating
    fun bind(message: MmsMessageRecord, isStartOfMessageCluster: Boolean, isEndOfMessageCluster: Boolean) {
        val audioSlide = message.slideDeck.audioSlide!!

        binding.voiceMessageViewLoader.isVisible = audioSlide.isInProgress
        val cornerRadii = MessageBubbleUtilities.calculateRadii(context, isStartOfMessageCluster, isEndOfMessageCluster, message.isOutgoing)
        cornerMask.setTopLeftRadius(cornerRadii[0])
        cornerMask.setTopRightRadius(cornerRadii[1])
        cornerMask.setBottomRightRadius(cornerRadii[2])
        cornerMask.setBottomLeftRadius(cornerRadii[3])

        // Obtain and set the last voice message duration (taken from the InputBarRecordingView) to use as an interim
        // value while the file is being processed. Should this VoiceMessageView be an audio file rather than a voice
        // message then the duration string will be "--:--" to indicate that we do not know the audio duration until
        // processing completes and that information is available from the audio extras, below.
        binding.voiceMessageViewDurationTextView.text = conversationActivityV2?.getLastRecordedVoiceMessageDurationString()

        // On initial upload (and while processing audio) we will exit at this point and then return when processing is complete
        if (audioSlide.isPendingDownload || audioSlide.isInProgress) {
            this.player = null
            return
        }

        val player = AudioSlidePlayer.createFor(context.applicationContext, audioSlide, this)
        this.player = player

        // This sets the final duration of the uploaded voice message
        (audioSlide.asAttachment() as? DatabaseAttachment)?.let { attachment ->
            attachmentDb.getAttachmentAudioExtras(attachment.attachmentId)?.let { audioExtras ->

                // This section sets the final formatted voice message duration
                if (audioExtras.durationMs > 0) {
                    durationMS = audioExtras.durationMs
                    val formattedVoiceMessageDuration = String.format("%01d:%02d", TimeUnit.MILLISECONDS.toMinutes(durationMS), TimeUnit.MILLISECONDS.toSeconds(durationMS) % 60)
                    binding.voiceMessageViewDurationTextView.text = formattedVoiceMessageDuration
                } else {
                    Log.w(TAG, "For some reason audioExtras.durationMs was NOT greater than zero!")
                    binding.voiceMessageViewDurationTextView.text = "--:--"
                }

                binding.voiceMessageViewDurationTextView.visibility = VISIBLE
            }
        }
    }

    override fun onPlayerStart(player: AudioSlidePlayer) { isPlaying = true  }
    override fun onPlayerStop(player: AudioSlidePlayer)  { isPlaying = false }

    override fun onPlayerProgress(player: AudioSlidePlayer, progress: Double, unused: Long) {
        if (progress == 1.0) {
            togglePlayback()
            handleProgressChanged(0.0)
            delegate?.playVoiceMessageAtIndexIfPossible(indexInAdapter + 1)
        } else {
            handleProgressChanged(progress)
        }
    }

    private fun handleProgressChanged(progress: Double) {
        this.progress = progress
        binding.voiceMessageViewDurationTextView.text = String.format("%01d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(durationMS - (progress * durationMS.toDouble()).roundToLong()),
            TimeUnit.MILLISECONDS.toSeconds(durationMS - (progress * durationMS.toDouble()).roundToLong()) % 60)
        val layoutParams = binding.progressView.layoutParams as RelativeLayout.LayoutParams
        layoutParams.width = (width.toFloat() * progress.toFloat()).roundToInt()
        binding.progressView.layoutParams = layoutParams
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        cornerMask.mask(canvas)
    }

    private fun renderIcon() {
        val iconID = if (isPlaying) R.drawable.exo_icon_pause else R.drawable.exo_icon_play
        binding.voiceMessagePlaybackImageView.setImageResource(iconID)
    }

    // endregion

    // region Interaction
    fun togglePlayback() {
        val player = this.player ?: return
        isPlaying = !isPlaying
        if (isPlaying) {
            player.play(progress)
        } else {
            player.stop()
        }
    }

    fun handleDoubleTap() {
        if (this.player == null) {
            Log.w(TAG, "Could not get player to adjust voice message playback speed.")
            return
        }
        this.player?.playbackSpeed = if (this.player?.playbackSpeed == 1f) 1.5f else 1f
    }
    // endregion
}

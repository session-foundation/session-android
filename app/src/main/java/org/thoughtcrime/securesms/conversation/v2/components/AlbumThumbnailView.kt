package org.thoughtcrime.securesms.conversation.v2.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import com.bumptech.glide.RequestManager
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import network.loki.messenger.databinding.AlbumThumbnailViewBinding
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.StringSubstitutionConstants.COUNT_KEY
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.MediaPreviewActivity
import org.thoughtcrime.securesms.components.CornerMask
import org.thoughtcrime.securesms.conversation.v2.utilities.ThumbnailView
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.util.ActivityDispatcher

class AlbumThumbnailView : RelativeLayout {
    companion object {
        const val MAX_ALBUM_DISPLAY_SIZE = 3
    }

    private val binding: AlbumThumbnailViewBinding by lazy { AlbumThumbnailViewBinding.bind(this) }

    // region Lifecycle
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private val cornerMask by lazy { CornerMask(this) }
    private var slides: List<Slide> = listOf()
    private var slideSize: Int = 0

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        cornerMask.mask(canvas)
    }
    // endregion

    // region Interaction

    fun calculateHitObject(event: MotionEvent, mms: MmsMessageRecord, threadRecipient: Recipient, downloadPendingAttachment: (DatabaseAttachment) -> Unit) {
        val rawXInt = event.rawX.toInt()
        val rawYInt = event.rawY.toInt()
        val eventRect = Rect(rawXInt, rawYInt, rawXInt, rawYInt)
        val testRect = Rect()
        // test each album child
        binding.albumCellContainer.findViewById<ViewGroup>(R.id.album_thumbnail_root)?.children?.forEachIndexed forEach@{ index, child ->
            child.getGlobalVisibleRect(testRect)
            if (testRect.contains(eventRect)) {
                // hit intersects with this particular child
                val slide = slides.getOrNull(index) ?: return@forEach
                // only open to downloaded images
                if (slide.isFailed) {
                    // Restart download here (on IO thread)
                    (slide.asAttachment() as? DatabaseAttachment)?.let { attachment ->
                        downloadPendingAttachment(attachment)
                    }
                }
                if (slide.isInProgress) return@forEach

                ActivityDispatcher.get(context)?.dispatchIntent { context ->
                    MediaPreviewActivity.getPreviewIntent(context, slide, mms, threadRecipient)
                }
            }
        }
    }

    fun clearViews() {
        binding.albumCellContainer.removeAllViews()
        slideSize = -1
    }

    fun bind(glideRequests: RequestManager, message: MmsMessageRecord,
             isStart: Boolean, isEnd: Boolean) {
        slides = message.slideDeck.thumbnailSlides.filter {
            it.isDone || (message.isOutgoing && it.uri != null)
        }
        if (slides.isEmpty()) {
            // this should never be encountered because it's checked by parent
            return
        }
        calculateRadius(isStart, isEnd, message.isOutgoing)

        // recreate cell views if different size to what we have already (for recycling)
        if (slides.size != this.slideSize) {
            binding.albumCellContainer.removeAllViews()
            LayoutInflater.from(context).inflate(layoutRes(slides.size), binding.albumCellContainer)
            val overflowed = slides.size > MAX_ALBUM_DISPLAY_SIZE
            binding.albumCellContainer.findViewById<TextView>(R.id.album_cell_overflow_text)?.let { overflowText ->
                // overflowText will be null if !overflowed
                overflowText.isVisible = overflowed // more than max album size
                val txt = Phrase.from(context, R.string.andMore)
                    .put(COUNT_KEY, slides.size - MAX_ALBUM_DISPLAY_SIZE)
                    .format()
                overflowText.text = txt
            }
            this.slideSize = slides.size
        }
        // iterate binding
        slides.take(MAX_ALBUM_DISPLAY_SIZE).forEachIndexed { position, slide ->
            val thumbnailView = getThumbnailView(position)
            thumbnailView.setImageResource(glideRequests, slide, isPreview = false)
        }
    }

    // endregion

    fun layoutRes(slideCount: Int) = when (slideCount) {
        1 -> R.layout.album_thumbnail_1    // single
        2 -> R.layout.album_thumbnail_2    // two side-by-side
        else -> R.layout.album_thumbnail_3 // three stacked with additional text
    }

    fun getThumbnailView(position: Int): ThumbnailView = when (position) {
        0 -> binding.albumCellContainer.findViewById<ViewGroup>(R.id.albumCellContainer).findViewById(R.id.album_cell_1)
        1 -> binding.albumCellContainer.findViewById<ViewGroup>(R.id.albumCellContainer).findViewById(R.id.album_cell_2)
        2 -> binding.albumCellContainer.findViewById<ViewGroup>(R.id.albumCellContainer).findViewById(R.id.album_cell_3)
        else -> throw Exception("Can't get thumbnail view for non-existent thumbnail at position: $position")
    }

    fun calculateRadius(isStart: Boolean, isEnd: Boolean, outgoing: Boolean) {
        val roundedDimen = context.resources.getDimension(R.dimen.message_corner_radius).toInt()
        val collapsedDimen = context.resources.getDimension(R.dimen.message_corner_collapse_radius).toInt()
        val (startTop, endTop, startBottom, endBottom) = when {
            // single message, consistent dimen
            isStart && isEnd -> intArrayOf(roundedDimen, roundedDimen, roundedDimen, roundedDimen)
            // start of message cluster, collapsed BL
            isStart -> intArrayOf(roundedDimen, roundedDimen, collapsedDimen, roundedDimen)
            // end of message cluster, collapsed TL
            isEnd -> intArrayOf(collapsedDimen, roundedDimen, roundedDimen, roundedDimen)
            // else in the middle, no rounding left side
            else -> intArrayOf(collapsedDimen, roundedDimen, collapsedDimen, roundedDimen)
        }
        // TL, TR, BR, BL (CW direction)
        cornerMask.setRadii(
                if (!outgoing) startTop else endTop, // TL
                if (!outgoing) endTop else startTop, // TR
                if (!outgoing) endBottom else startBottom, // BR
                if (!outgoing) startBottom else endBottom // BL
        )
    }

}
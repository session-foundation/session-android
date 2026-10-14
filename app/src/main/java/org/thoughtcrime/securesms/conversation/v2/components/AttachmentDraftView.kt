package org.thoughtcrime.securesms.conversation.v2.components

import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.bumptech.glide.RequestManager
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewAttachmentDraftBinding
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.util.toPx

/**
 * The attachment waiting in the input bar to be sent, with the means to drop it again.
 */
class AttachmentDraftView : LinearLayout {
    private lateinit var binding: ViewAttachmentDraftBinding
    var delegate: AttachmentDraftViewDelegate? = null

    constructor(context: Context) : super(context) { initialize() }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { initialize() }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { initialize() }

    private fun initialize() {
        binding = ViewAttachmentDraftBinding.inflate(LayoutInflater.from(context), this, true)
        binding.attachmentDraftThumbnail.root.clipToOutline = true
        binding.attachmentDraftCancelButton.contentDescription = context.getString(R.string.remove)
        binding.attachmentDraftCancelButton.setOnClickListener { delegate?.cancelAttachmentDraft() }
    }

    fun update(glide: RequestManager, slide: Slide) {
        binding.attachmentDraftFilenameTextView.text = slide.filename
        binding.attachmentDraftFileSizeTextView.text = Formatter.formatFileSize(context, slide.fileSize)

        // A document carries a thumbnailUri as readily as a photo does, so hasImage() rather than the
        // URI decides: without it the empty thumbnail view covers the file icon standing in for it.
        val thumbnail = slide.thumbnailUri.takeIf { slide.hasImage() }
        binding.attachmentDraftThumbnail.root.isVisible = thumbnail != null
        if (thumbnail != null) {
            binding.attachmentDraftThumbnail.root.setRoundedCorners(toPx(4, resources))
            binding.attachmentDraftThumbnail.root.setImageResource(glide, ImageSlide(context, thumbnail, slide.filename, slide.fileSize, 0, 0, null), false)
        }
    }
}

interface AttachmentDraftViewDelegate {

    fun cancelAttachmentDraft()
}

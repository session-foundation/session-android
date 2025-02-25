package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.use
import androidx.core.text.toSpannable
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewQuoteBinding
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.database.SessionContactDatabase
import com.bumptech.glide.RequestManager
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.getAccentColor
import org.thoughtcrime.securesms.util.toPx
import javax.inject.Inject

// There's quite some calculation going on here. It's a bit complex so don't make changes
// if you don't need to. If you do then test:
// • Quoted text in both private chats and group chats
// • Quoted images and videos in both private chats and group chats
// • Quoted voice messages and documents in both private chats and group chats
// • All of the above in both dark mode and light mode
@AndroidEntryPoint
class QuoteView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ConstraintLayout(context, attrs) {

    @Inject lateinit var contactDb: SessionContactDatabase

    private val binding: ViewQuoteBinding by lazy { ViewQuoteBinding.bind(this) }
    private val vPadding by lazy { toPx(6, resources) }
    var delegate: QuoteViewDelegate? = null
    private val mode: Mode

    enum class Mode { Regular, Draft }

    init {
        mode = attrs?.let { attrSet ->
            context.obtainStyledAttributes(attrSet, R.styleable.QuoteView).use { typedArray ->
                val modeIndex = typedArray.getInt(R.styleable.QuoteView_quote_mode,  0)
                Mode.values()[modeIndex]
            }
        } ?: Mode.Regular
    }

    // region Lifecycle
    override fun onFinishInflate() {
        super.onFinishInflate()
        when (mode) {
            Mode.Draft -> binding.quoteViewCancelButton.setOnClickListener { delegate?.cancelQuoteDraft() }
            Mode.Regular -> {
                binding.quoteViewCancelButton.isVisible = false
                binding.mainQuoteViewContainer.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.transparent, context.theme))
            }
        }
    }
    // endregion

    // region Updating
    fun bind(authorPublicKey: String, body: String?, attachments: SlideDeck?, thread: Recipient,
        isOutgoingMessage: Boolean, isOpenGroupInvitation: Boolean, threadID: Long,
        isOriginalMissing: Boolean, glide: RequestManager) {
        // Author
        val author = contactDb.getContactWithAccountID(authorPublicKey)
        val localNumber = TextSecurePreferences.getLocalNumber(context)
        val quoteIsLocalUser = localNumber != null && authorPublicKey == localNumber

        val authorDisplayName =
            if (quoteIsLocalUser) context.getString(R.string.you)
            else author?.displayName(Contact.contextForRecipient(thread)) ?: "${authorPublicKey.take(4)}...${authorPublicKey.takeLast(4)}"
        binding.quoteViewAuthorTextView.text = authorDisplayName
        binding.quoteViewAuthorTextView.setTextColor(getTextColor(isOutgoingMessage))
        // Body
        binding.quoteViewBodyTextView.text = if (isOpenGroupInvitation)
            resources.getString(R.string.communityInvitation)
        else MentionUtilities.highlightMentions(
            text = (body ?: "").toSpannable(),
            isOutgoingMessage = isOutgoingMessage,
            isQuote = true,
            threadID = threadID,
            context = context
        )
        binding.quoteViewBodyTextView.setTextColor(getTextColor(isOutgoingMessage))
        // Accent line / attachment preview
        val hasAttachments = (attachments != null && attachments.asAttachments().isNotEmpty()) && !isOriginalMissing
        binding.quoteViewAccentLine.isVisible = !hasAttachments
        binding.quoteViewAttachmentPreviewContainer.isVisible = hasAttachments
        if (!hasAttachments) {
            binding.quoteViewAccentLine.setBackgroundColor(getLineColor(isOutgoingMessage))
        } else if (attachments != null) {
            binding.quoteViewAttachmentPreviewImageView.imageTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.white, context.theme))
            val backgroundColor = context.getAccentColor()
            binding.quoteViewAttachmentPreviewContainer.backgroundTintList = ColorStateList.valueOf(backgroundColor)
            binding.quoteViewAttachmentPreviewImageView.isVisible = false
            binding.quoteViewAttachmentThumbnailImageView.root.isVisible = false
            when {
                attachments.audioSlide != null -> {
                    binding.quoteViewAttachmentPreviewImageView.setImageResource(R.drawable.ic_microphone)
                    binding.quoteViewAttachmentPreviewImageView.isVisible = true
                    // A missing file name is the legacy way to determine if an audio attachment is
                    // a voice note vs. other arbitrary audio attachments.
                    val attachment = attachments.asAttachments().firstOrNull()
                    val isVoiceNote = attachment?.isVoiceNote == true ||
                            attachment != null && attachment.fileName.isNullOrEmpty()
                    binding.quoteViewBodyTextView.text = if (isVoiceNote) {
                        resources.getString(R.string.messageVoice)
                    } else {
                        resources.getString(R.string.audio)
                    }
                }
                attachments.documentSlide != null -> {
                    binding.quoteViewAttachmentPreviewImageView.setImageResource(R.drawable.ic_document_large_light)
                    binding.quoteViewAttachmentPreviewImageView.isVisible = true
                    binding.quoteViewBodyTextView.text = resources.getString(R.string.document)
                }
                attachments.thumbnailSlide != null -> {
                    val slide = attachments.thumbnailSlide!!
                    // This internally fetches the thumbnail
                    binding.quoteViewAttachmentThumbnailImageView
                        .root.setRoundedCorners(toPx(4, resources))
                    binding.quoteViewAttachmentThumbnailImageView.root.setImageResource(glide, slide, false)
                    binding.quoteViewAttachmentThumbnailImageView.root.isVisible = true
                    binding.quoteViewBodyTextView.text = if (MediaUtil.isVideo(slide.asAttachment())) resources.getString(R.string.video) else resources.getString(R.string.image)
                }
            }
        }
    }
    // endregion

    // region Convenience
    @ColorInt private fun getLineColor(isOutgoingMessage: Boolean): Int {
        return when {
            mode == Mode.Regular && !isOutgoingMessage -> context.getColorFromAttr(R.attr.colorAccent)
            mode == Mode.Regular -> context.getColorFromAttr(R.attr.message_sent_text_color)
            else -> context.getColorFromAttr(R.attr.colorAccent)
        }
    }

    @ColorInt private fun getTextColor(isOutgoingMessage: Boolean): Int {
        if (mode == Mode.Draft) { return context.getColorFromAttr(android.R.attr.textColorPrimary) }
        return if (!isOutgoingMessage) {
            context.getColorFromAttr(R.attr.message_received_text_color)
        } else  {
            context.getColorFromAttr(R.attr.message_sent_text_color)
        }
    }

    // endregion
}

interface QuoteViewDelegate {

    fun cancelQuoteDraft()
}
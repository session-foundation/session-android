package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.use
import androidx.core.graphics.toColor
import androidx.core.text.toSpannable
import androidx.core.view.isVisible
import com.bumptech.glide.RequestManager
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewQuoteBinding
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.truncateIdForDisplay
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.database.SessionContactDatabase
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.proBadgeColorOutgoing
import org.thoughtcrime.securesms.ui.proBadgeColorStandard
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.util.MediaUtil
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

    @Inject lateinit var proStatusManager: ProStatusManager

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
    fun bind(authorRecipient: Recipient, body: String?, attachments: SlideDeck?, thread: Recipient,
        isOutgoingMessage: Boolean, isOpenGroupInvitation: Boolean, threadID: Long,
        isOriginalMissing: Boolean, glide: RequestManager) {
        // Author
        val authorPublicKey = authorRecipient.address.toString()
        val author = contactDb.getContactWithAccountID(authorPublicKey)
        val localNumber = TextSecurePreferences.getLocalNumber(context)
        val quoteIsLocalUser = localNumber != null && authorPublicKey == localNumber

        val authorDisplayName =
            if (quoteIsLocalUser) context.getString(R.string.you)
            else author?.displayName(Contact.contextForRecipient(thread)) ?: truncateIdForDisplay(authorPublicKey)

        val textColor = getTextColor(isOutgoingMessage)

        // set up quote author
        binding.quoteAuthor.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            var modifier: Modifier = Modifier
            if(mode == Mode.Regular){
                modifier = modifier.widthIn(max = 240.dp) // this value is hardcoded in the xml files > when we move to composable messages this will be handled better internally
            }

            setThemedContent {
                ProBadgeText(
                    modifier = modifier,
                    text = authorDisplayName, //todo badge we need to rework te naming logic to get the name (no account id for blinded here...) - waiting on the Recipient refactor
                    textStyle = LocalType.current.small.bold().copy(color = Color(textColor)),
                    showBadge = proStatusManager.shouldShowProBadge(authorRecipient.address),
                    badgeColors = if(isOutgoingMessage && mode == Mode.Regular) proBadgeColorOutgoing()
                    else proBadgeColorStandard()
                )
            }
        }

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
        binding.quoteViewBodyTextView.setTextColor(textColor)
        // Accent line / attachment preview
        val hasAttachments = (attachments != null && attachments.asAttachments().isNotEmpty()) && !isOriginalMissing
        binding.quoteViewAccentLine.isVisible = !hasAttachments
        binding.quoteViewAttachmentPreviewContainer.isVisible = hasAttachments
        if (!hasAttachments) {
            binding.quoteViewAccentLine.setBackgroundColor(getLineColor(isOutgoingMessage))
        } else if (attachments != null) {
            binding.quoteViewAttachmentPreviewImageView.imageTintList = ColorStateList.valueOf(textColor)
            binding.quoteViewAttachmentPreviewImageView.isVisible = true
            binding.quoteViewAttachmentThumbnailImageView.root.isVisible = false
            when {
                attachments.audioSlide != null -> {
                    val isVoiceNote = attachments.isVoiceNote
                    if (isVoiceNote) {
                        updateQuoteTextIfEmpty(resources.getString(R.string.messageVoice))
                        binding.quoteViewAttachmentPreviewImageView.setImageResource(R.drawable.ic_mic)
                    } else {
                        updateQuoteTextIfEmpty(resources.getString(R.string.audio))
                        binding.quoteViewAttachmentPreviewImageView.setImageResource(R.drawable.ic_volume_2)
                    }
                }
                attachments.documentSlide != null -> {
                    binding.quoteViewAttachmentPreviewImageView.setImageResource(R.drawable.ic_file)
                    updateQuoteTextIfEmpty(resources.getString(R.string.document))
                }
                attachments.thumbnailSlide != null -> {
                    val slide = attachments.thumbnailSlide!!

                    if (MediaUtil.isVideo(slide.asAttachment())){
                        updateQuoteTextIfEmpty(resources.getString(R.string.video))
                        binding.quoteViewAttachmentPreviewImageView.setImageResource(R.drawable.ic_square_play)
                    } else {
                        updateQuoteTextIfEmpty(resources.getString(R.string.image))
                        binding.quoteViewAttachmentPreviewImageView.setImageResource(R.drawable.ic_image)
                    }

                    // display the image if we are in the appropriate state
                    if(attachments.asAttachments().all { it.isDone }) {
                        binding.quoteViewAttachmentThumbnailImageView
                            .root.setRoundedCorners(toPx(4, resources))
                        binding.quoteViewAttachmentThumbnailImageView.root.setImageResource(
                            glide,
                            slide,
                            false
                        )
                        binding.quoteViewAttachmentThumbnailImageView.root.isVisible = true
                        binding.quoteViewAttachmentPreviewImageView.isVisible = false
                    }

                }
            }
        }
    }

    private fun updateQuoteTextIfEmpty(text: String){
        if(binding.quoteViewBodyTextView.text.isNullOrEmpty()){
            binding.quoteViewBodyTextView.text = text
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
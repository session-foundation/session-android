package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.text.getSpans
import androidx.core.text.toSpannable
import androidx.core.view.children
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewVisibleMessageContentBinding
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.ThemeUtil
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.modifyLayoutParams
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.messages.AttachmentControlView.AttachmentType.AUDIO
import org.thoughtcrime.securesms.conversation.v2.messages.AttachmentControlView.AttachmentType.DOCUMENT
import org.thoughtcrime.securesms.conversation.v2.messages.AttachmentControlView.AttachmentType.IMAGE
import org.thoughtcrime.securesms.conversation.v2.messages.AttachmentControlView.AttachmentType.VIDEO
import org.thoughtcrime.securesms.conversation.v2.messages.AttachmentControlView.AttachmentType.VOICE
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.conversation.v2.utilities.ModalURLSpan
import org.thoughtcrime.securesms.conversation.v2.utilities.TextUtilities.getIntersectedModalSpans
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.util.GlowViewUtilities
import org.thoughtcrime.securesms.util.SearchUtil
import org.thoughtcrime.securesms.util.getAccentColor
import java.util.Locale
import kotlin.math.roundToInt

class VisibleMessageContentView : ConstraintLayout {
    private val binding: ViewVisibleMessageContentBinding by lazy { ViewVisibleMessageContentBinding.bind(this) }
    var onContentDoubleTap: (() -> Unit)? = null
    var delegate: VisibleMessageViewDelegate? = null
    var indexInAdapter: Int = -1

    // region Lifecycle
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    // endregion

    // region Updating
    fun bind(
        message: MessageRecord,
        isStartOfMessageCluster: Boolean = true,
        isEndOfMessageCluster: Boolean = true,
        glide: RequestManager = Glide.with(this),
        thread: Recipient,
        searchQuery: String? = null,
        downloadPendingAttachment: (DatabaseAttachment) -> Unit,
        retryFailedAttachments: (List<DatabaseAttachment>) -> Unit,
        suppressThumbnails: Boolean = false
    ) {
        // Background
        val color = if (message.isOutgoing) context.getAccentColor()
        else context.getColorFromAttr(R.attr.message_received_background_color)
        binding.contentParent.mainColor = color
        binding.documentView.root.backgroundTintList = ColorStateList.valueOf(color)
        binding.voiceMessageView.root.backgroundTintList = ColorStateList.valueOf(color)
        binding.contentParent.cornerRadius = resources.getDimension(R.dimen.message_corner_radius)

        val mediaDownloaded = message is MmsMessageRecord && message.slideDeck.asAttachments().all { it.isDone }
        val mediaInProgress = message is MmsMessageRecord && message.slideDeck.asAttachments().any { it.isInProgress }
        val hasFailed = message is MmsMessageRecord && message.slideDeck.asAttachments().any { it.isFailed }
        val hasExpired = haveAttachmentsExpired(message)
        val overallAttachmentState = when {
            mediaDownloaded -> AttachmentState.DONE
            hasExpired -> AttachmentState.EXPIRED
            hasFailed -> AttachmentState.FAILED
            mediaInProgress -> AttachmentState.DOWNLOADING
            else -> AttachmentState.PENDING
        }

        val databaseAttachments = (message as? MmsMessageRecord)?.slideDeck?.asAttachments()?.filterIsInstance<DatabaseAttachment>()

        // reset visibilities / containers
        onContentClick.clear()
        binding.albumThumbnailView.root.clearViews()
        onContentDoubleTap = null

        if (message.isDeleted) {
            binding.contentParent.isVisible = true
            binding.deletedMessageView.root.isVisible = true
            binding.deletedMessageView.root.bind(message, getTextColor(context, message))
            binding.bodyTextView.isVisible = false
            binding.quoteView.root.isVisible = false
            binding.linkPreviewView.root.isVisible = false
            binding.voiceMessageView.root.isVisible = false
            binding.documentView.root.isVisible = false
            binding.albumThumbnailView.root.isVisible = false
            binding.openGroupInvitationView.root.isVisible = false
            return
        } else {
            binding.deletedMessageView.root.isVisible = false
        }

        // Note: Need to clear the body to prevent the message bubble getting incorrectly
        // sized based on text content from a recycled view
        binding.bodyTextView.text = null
        binding.quoteView.root.isVisible = message is MmsMessageRecord && message.quote != null
        // if a quote is by itself we should add bottom padding
        binding.quoteView.root.setPadding(
            binding.quoteView.root.paddingStart,
            binding.quoteView.root.paddingTop,
            binding.quoteView.root.paddingEnd,
            if(message.body.isNotEmpty()) 0 else
                context.resources.getDimensionPixelSize(R.dimen.message_spacing)
        )
        binding.linkPreviewView.root.isVisible = message is MmsMessageRecord && message.linkPreviews.isNotEmpty()
        binding.attachmentControlView.root.isVisible = false
        binding.voiceMessageView.root.isVisible = false
        binding.documentView.root.isVisible = false
        binding.albumThumbnailView.root.isVisible = false
        binding.openGroupInvitationView.root.isVisible = message.isOpenGroupInvitation

        var hideBody = false

        if (message is MmsMessageRecord && message.quote != null) {
            binding.quoteView.root.isVisible = true
            val quote = message.quote!!
            val quoteText = if (quote.isOriginalMissing) {
                context.getString(R.string.messageErrorOriginal)
            } else {
                quote.text
            }
            binding.quoteView.root.bind(quote.author.toString(), quoteText, quote.attachment, thread,
                message.isOutgoing, message.isOpenGroupInvitation, message.threadId,
                quote.isOriginalMissing, glide)
            onContentClick.add { event ->
                val r = Rect()
                binding.quoteView.root.getGlobalVisibleRect(r)
                if (r.contains(event.rawX.roundToInt(), event.rawY.roundToInt())) {
                    delegate?.highlightMessageFromTimestamp(quote.id)
                }
            }
        }

        if (message is MmsMessageRecord) {
            databaseAttachments?.forEach { attach ->
                downloadPendingAttachment(attach)
            }
            message.linkPreviews.forEach { preview ->
                val previewThumbnail = preview.getThumbnail().orNull() as? DatabaseAttachment ?: return@forEach
                downloadPendingAttachment(previewThumbnail)
            }
        }

        when {
            // LINK PREVIEW
            message is MmsMessageRecord && message.linkPreviews.isNotEmpty() -> {
                binding.linkPreviewView.root.bind(message, glide, isStartOfMessageCluster, isEndOfMessageCluster)
                onContentClick.add { event -> binding.linkPreviewView.root.calculateHit(event) }

                // When in a link preview ensure the bodyTextView can expand to the full width
                binding.bodyTextView.maxWidth = binding.linkPreviewView.root.layoutParams.width
            }

            // AUDIO
            message is MmsMessageRecord && message.slideDeck.audioSlide != null -> {

                // Show any text message associated with the audio message (which may be a voice clip - but could also be a mp3 or such)
                hideBody = false

                // Audio attachment
                if (overallAttachmentState == AttachmentState.DONE || message.isOutgoing) {
                    binding.voiceMessageView.root.isVisible = true
                    binding.voiceMessageView.root.indexInAdapter = indexInAdapter
                    binding.voiceMessageView.root.delegate = context as? ConversationActivityV2
                    binding.voiceMessageView.root.bind(message, isStartOfMessageCluster, isEndOfMessageCluster)
                    // We have to use onContentClick (rather than a click listener directly on the voice
                    // message view) so as to not interfere with all the other gestures.
                    onContentClick.add { binding.voiceMessageView.root.togglePlayback() }
                    onContentDoubleTap = { binding.voiceMessageView.root.handleDoubleTap() }
                    binding.attachmentControlView.root.isVisible = false
                } else {
                    val attachment = message.slideDeck.audioSlide?.asAttachment() as? DatabaseAttachment
                    attachment?.let {
                        showAttachmentControl(
                            thread = thread,
                            message = message,
                            attachments = listOf(it),
                            type = if (it.isVoiceNote) VOICE
                            else AUDIO,
                            overallAttachmentState,
                            retryFailedAttachments = retryFailedAttachments
                        )
                    }
                }
            }

            //todo: ATTACHMENT should the glowView encompass the whole message instead of just the body? Currently tapped quotes only highlight text messages, not images nor attachment control

            // DOCUMENT
            message is MmsMessageRecord && message.slideDeck.documentSlide != null -> {
                // Show any message that came with the attached document
                hideBody = false

                // Document attachment
                if (overallAttachmentState == AttachmentState.DONE  || message.isOutgoing) {
                    binding.attachmentControlView.root.isVisible = false

                    binding.documentView.root.isVisible = true
                    binding.documentView.root.bind(message, getTextColor(context, message))

                    message.slideDeck.documentSlide?.let { slide ->
                        if(!mediaInProgress) { // do not attempt to open a doc in progress of downloading
                            onContentClick.add {
                                // open the document when tapping it
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW)
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    intent.setDataAndType(
                                        PartAuthority.getAttachmentPublicUri(slide.uri),
                                        slide.contentType
                                    )

                                    context.startActivity(intent)
                                } catch (e: ActivityNotFoundException) {
                                    Log.e("VisibleMessageContentView", "Error opening document", e)
                                    Toast.makeText(
                                        context,
                                        R.string.attachmentsErrorOpen,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                } else {
                    (message.slideDeck.documentSlide?.asAttachment() as? DatabaseAttachment)?.let {
                        showAttachmentControl(
                            thread = thread,
                            message = message,
                            attachments = listOf(it),
                            type = DOCUMENT,
                            overallAttachmentState,
                            retryFailedAttachments = retryFailedAttachments
                        )
                    }
                }
            }

            // IMAGE / VIDEO
            message is MmsMessageRecord && message.slideDeck.asAttachments().isNotEmpty() -> {
                hideBody = false

                if (overallAttachmentState == AttachmentState.DONE || message.isOutgoing) {
                    if(!suppressThumbnails) { // suppress thumbnail should hide the image, but we still want to show the attachment control if the state demands it

                        binding.attachmentControlView.root.isVisible = false

                        // isStart and isEnd of cluster needed for calculating the mask for full bubble image groups
                        // bind after add view because views are inflated and calculated during bind
                        binding.albumThumbnailView.root.isVisible = true
                        binding.albumThumbnailView.root.bind(
                            glideRequests = glide,
                            message = message,
                            isStart = isStartOfMessageCluster,
                            isEnd = isEndOfMessageCluster
                        )
                        binding.albumThumbnailView.root.modifyLayoutParams<LayoutParams> {
                            horizontalBias = if (message.isOutgoing) 1f else 0f
                        }
                        onContentClick.add { event ->
                            binding.albumThumbnailView.root.calculateHitObject(
                                event,
                                message,
                                thread,
                                downloadPendingAttachment
                            )
                        }
                    }
                } else {
                    databaseAttachments?.let {
                        showAttachmentControl(
                            thread = thread,
                            message = message,
                            attachments = it,
                            type = if (message.slideDeck.hasVideo()) VIDEO
                            else IMAGE,
                            state = overallAttachmentState,
                            retryFailedAttachments = retryFailedAttachments
                        )
                    }
                }
            }
            message.isOpenGroupInvitation -> {
                hideBody = true
                binding.openGroupInvitationView.root.bind(message, getTextColor(context, message))
                onContentClick.add { binding.openGroupInvitationView.root.joinOpenGroup() }
            }
        }

        binding.bodyTextView.isVisible = message.body.isNotEmpty() && !hideBody
        binding.contentParent.apply { isVisible = children.any { it.isVisible } }

        if (message.body.isNotEmpty() && !hideBody) {
            val color = getTextColor(context, message)
            binding.bodyTextView.setTextColor(color)
            binding.bodyTextView.setLinkTextColor(color)
            val body = getBodySpans(context, message, searchQuery)
            binding.bodyTextView.text = body
            onContentClick.add { e: MotionEvent ->
                binding.bodyTextView.getIntersectedModalSpans(e).iterator().forEach { span ->
                    span.onClick(binding.bodyTextView)
                }
            }
        }
        binding.contentParent.modifyLayoutParams<ConstraintLayout.LayoutParams> {
            horizontalBias = if (message.isOutgoing) 1f else 0f
        }

        binding.attachmentControlView.root.modifyLayoutParams<ConstraintLayout.LayoutParams> {
            horizontalBias = if (message.isOutgoing) 1f else 0f
        }
    }

    private fun showAttachmentControl(
        thread: Recipient,
        message: MmsMessageRecord,
        attachments: List<DatabaseAttachment>,
        type: AttachmentControlView.AttachmentType,
        state: AttachmentState,
        retryFailedAttachments: (List<DatabaseAttachment>) -> Unit,
    ){
        binding.attachmentControlView.root.isVisible = true
        binding.albumThumbnailView.root.clearViews()

        binding.attachmentControlView.root.bind(
            attachmentType = type,
            textColor = getTextColor(context,message),
            state = state,
            allMessageAttachments = message.slideDeck.slides
        )

        when(state) {
            // While downloads haven't been enabled for this convo, show a confirmation dialog
            AttachmentState.PENDING -> {
                onContentClick.add {
                    binding.attachmentControlView.root.showDownloadDialog(
                        thread,
                        attachments.first()
                    )
                }
            }

            // Attempt to redownload a failed attachment on tap
            AttachmentState.FAILED -> {
                onContentClick.add {
                    retryFailedAttachments(attachments)
                }
            }

            // no click actions for other cases
            else -> {}
        }
    }

    private fun haveAttachmentsExpired(message: MessageRecord): Boolean =
    // expired attachments are for Mms records only
    message is MmsMessageRecord &&
            // with a state marked as expired
            (message.slideDeck.asAttachments().any { it.transferState == AttachmentState.EXPIRED.value } ||
            // with a state marked as downloaded yet without a URI attached
            (!message.hasAttachmentUri() && message.slideDeck.asAttachments().all { it.isDone }))

    private val onContentClick: MutableList<((event: MotionEvent) -> Unit)> = mutableListOf()

    fun onContentClick(event: MotionEvent) {
        onContentClick.forEach { clickHandler -> clickHandler.invoke(event) }
    }

    private fun ViewVisibleMessageContentBinding.barrierViewsGone(): Boolean =
        listOf<View>(albumThumbnailView.root, linkPreviewView.root, voiceMessageView.root, quoteView.root).none { it.isVisible }

    fun recycle() {
        arrayOf(
            binding.deletedMessageView.root,
            binding.attachmentControlView.root,
            binding.voiceMessageView.root,
            binding.openGroupInvitationView.root,
            binding.documentView.root,
            binding.quoteView.root,
            binding.linkPreviewView.root,
            binding.albumThumbnailView.root,
            binding.bodyTextView
        ).forEach { view: View -> view.isVisible = false }
    }

    fun playVoiceMessage() {
        binding.voiceMessageView.root.togglePlayback()
    }

    fun playHighlight() {
        // Show the highlight colour immediately then slowly fade out
        val targetColor = if (ThemeUtil.isDarkTheme(context)) context.getAccentColor() else resources.getColor(R.color.black, context.theme)
        val clearTargetColor = ColorUtils.setAlphaComponent(targetColor, 0)
        binding.contentParent.numShadowRenders = if (ThemeUtil.isDarkTheme(context)) 3 else 1
        binding.contentParent.sessionShadowColor = targetColor
        GlowViewUtilities.animateShadowColorChange(binding.contentParent, targetColor, clearTargetColor, 1600)
    }
    // endregion

    // region Convenience
    companion object {

        fun getBodySpans(context: Context, message: MessageRecord, searchQuery: String?): Spannable {
            var body = message.body.toSpannable()

            body = MentionUtilities.highlightMentions(
                text = body,
                isOutgoingMessage = message.isOutgoing,
                threadID = message.threadId,
                context = context
            )
            body = SearchUtil.getHighlightedSpan(Locale.getDefault(),
                { BackgroundColorSpan(Color.WHITE) }, body, searchQuery)
            body = SearchUtil.getHighlightedSpan(Locale.getDefault(),
                { ForegroundColorSpan(Color.BLACK) }, body, searchQuery)

            Linkify.addLinks(body, Linkify.WEB_URLS)

            // replace URLSpans with ModalURLSpans
            body.getSpans<URLSpan>(0, body.length).toList().forEach { urlSpan ->
                val updatedUrl = urlSpan.url.let { it.toHttpUrlOrNull().toString() }
                val replacementSpan = ModalURLSpan(updatedUrl) { url ->
                    val activity = context as? ConversationActivityV2
                    activity?.showOpenUrlDialog(url)
                }
                val start = body.getSpanStart(urlSpan)
                val end = body.getSpanEnd(urlSpan)
                val flags = body.getSpanFlags(urlSpan)
                body.removeSpan(urlSpan)
                body.setSpan(replacementSpan, start, end, flags)
            }
            return body
        }

        @ColorInt
        fun getTextColor(context: Context, message: MessageRecord): Int = context.getColorFromAttr(
            if (message.isOutgoing) R.attr.message_sent_text_color else R.attr.message_received_text_color
        )
    }
    // endregion
}

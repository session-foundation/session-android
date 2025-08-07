package org.thoughtcrime.securesms.home

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewConversationBinding
import org.session.libsession.utilities.ThemeUtil
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities.highlightMentions
import org.thoughtcrime.securesms.database.RecipientDatabase.NOTIFY_TYPE_ALL
import org.thoughtcrime.securesms.database.RecipientDatabase.NOTIFY_TYPE_NONE
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.UnreadStylingHelper
import org.thoughtcrime.securesms.util.getConversationUnread
import javax.inject.Inject

@AndroidEntryPoint
class ConversationView : LinearLayout {

    @Inject lateinit var configFactory: ConfigFactory
    @Inject lateinit var dateUtils: DateUtils
    @Inject lateinit var proStatusManager: ProStatusManager

    private val binding: ViewConversationBinding by lazy { ViewConversationBinding.bind(this) }
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    var thread: ThreadRecord? = null

    // region Lifecycle
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onFinishInflate() {
        super.onFinishInflate()
        layoutParams = RecyclerView.LayoutParams(screenWidth, RecyclerView.LayoutParams.WRAP_CONTENT)
    }
    // endregion

    // region Updating
    fun bind(thread: ThreadRecord, isTyping: Boolean) {
        this.thread = thread
        binding.iconPinned.isVisible = thread.isPinned

        val isConversationUnread = (configFactory.withUserConfigs { it.convoInfoVolatile.getConversationUnread(thread) } || !thread.isRead)
        val unreadCount = thread.unreadCount
        val hasUnreadCount = unreadCount > 0
        val isMarkedUnread = !hasUnreadCount && isConversationUnread

        binding.root.background = UnreadStylingHelper.getUnreadBackground(context,
            hasUnreadCount || isMarkedUnread)

        if (thread.recipient.isBlocked) {
            binding.accentView.setBackgroundColor(ThemeUtil.getThemedColor(context, R.attr.danger))
            binding.accentView.visibility = View.VISIBLE
        } else {
            binding.accentView.background = UnreadStylingHelper.getAccentBackground(context)
            // Using thread.isRead we can determine if the last message was our own, and display it as 'read' even though previous messages may not be
            // This would also not trigger the disappearing message timer which may or may not be desirable
            binding.accentView.visibility = if(hasUnreadCount) View.VISIBLE else View.INVISIBLE
        }

        binding.unreadCountTextView.text = UnreadStylingHelper.formatUnreadCount(unreadCount)

        binding.unreadCountIndicator.isVisible = hasUnreadCount
        binding.unreadMentionIndicator.isVisible = (thread.unreadMentionCount != 0 && thread.recipient.address.isGroupOrCommunity)
        binding.markedUnreadIndicator.isVisible = isMarkedUnread

        val senderDisplayName = getTitle(thread.recipient)

        // set up thread name
        binding.conversationViewDisplayName.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setThemedContent {
                ProBadgeText(
                    text = senderDisplayName,
                    textStyle = LocalType.current.h8.bold().copy(color = LocalColors.current.text),
                    showBadge = proStatusManager.shouldShowProBadge(thread.recipient.address)
                            && !thread.recipient.isLocalNumber,
                )
            }
        }

        binding.timestampTextView.text = thread.date.takeIf { it != 0L }?.let { dateUtils.getDisplayFormattedTimeSpanString(
            it
        ) }

        val recipient = thread.recipient
        binding.muteIndicatorImageView.isVisible = recipient.isMuted || recipient.notifyType != NOTIFY_TYPE_ALL

        val drawableRes = if (recipient.isMuted || recipient.notifyType == NOTIFY_TYPE_NONE) {
            R.drawable.ic_volume_off
        } else {
            R.drawable.ic_at_sign
        }

        binding.muteIndicatorImageView.setImageResource(drawableRes)

        val snippet =  highlightMentions(
            text = thread.getDisplayBody(context),
            formatOnly = true, // no styling here, only text formatting
            threadID = thread.threadId,
            context = context
        )

        binding.snippetTextView.apply {
            text = snippet
            typeface = UnreadStylingHelper.getUnreadTypeface(hasUnreadCount)
            visibility = if (isTyping) View.GONE else View.VISIBLE
        }

        if (isTyping) {
            binding.typingIndicatorView.root.startAnimation()
        } else {
            binding.typingIndicatorView.root.stopAnimation()
        }

        binding.typingIndicatorView.root.visibility = if (isTyping) View.VISIBLE else View.GONE
        binding.statusIndicatorImageView.visibility = View.VISIBLE
        binding.statusIndicatorImageView.imageTintList = ColorStateList.valueOf(ThemeUtil.getThemedColor(context, android.R.attr.textColorTertiary)) // tertiary in the current xml styling is actually what figma uses as secondary text color...

        when {
            !thread.isOutgoing || thread.lastMessage == null -> binding.statusIndicatorImageView.visibility = View.GONE

            thread.isFailed -> {
                val drawable = ContextCompat.getDrawable(context, R.drawable.ic_triangle_alert)?.mutate()
                binding.statusIndicatorImageView.setImageDrawable(drawable)
                binding.statusIndicatorImageView.imageTintList = ColorStateList.valueOf(ThemeUtil.getThemedColor(context, R.attr.danger))
            }
            thread.isPending -> binding.statusIndicatorImageView.setImageResource(R.drawable.ic_circle_dots_custom)
            thread.isRead -> binding.statusIndicatorImageView.setImageResource(R.drawable.ic_eye)
            else -> binding.statusIndicatorImageView.setImageResource(R.drawable.ic_circle_check)
        }

        binding.profilePictureView.update(thread.recipient)
    }

    fun recycle() { binding.profilePictureView.recycle() }

    private fun getTitle(recipient: Recipient): String = when {
        recipient.isLocalNumber -> context.getString(R.string.noteToSelf)
        else -> recipient.name // Internally uses the Contact API
    }
    // endregion
}

package org.thoughtcrime.securesms.home

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
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
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.getAccentColor
import org.thoughtcrime.securesms.util.getConversationUnread
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ConversationView : LinearLayout {

    @Inject lateinit var configFactory: ConfigFactory

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
        if (thread.isPinned) {
            binding.conversationViewDisplayNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                R.drawable.ic_pin,
                0
            )
        } else {
            binding.conversationViewDisplayNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }
        binding.root.background = if (thread.unreadCount > 0) {
            ContextCompat.getDrawable(context, R.drawable.conversation_unread_background)
        } else {
            ContextCompat.getDrawable(context, R.drawable.conversation_view_background)
        }
        val unreadCount = thread.unreadCount
        if (thread.recipient.isBlocked) {
            binding.accentView.setBackgroundColor(ThemeUtil.getThemedColor(context, R.attr.danger))
            binding.accentView.visibility = View.VISIBLE
        } else {
            val accentColor = context.getAccentColor()
            val background = ColorDrawable(accentColor)
            binding.accentView.background = background
            // Using thread.isRead we can determine if the last message was our own, and display it as 'read' even though previous messages may not be
            // This would also not trigger the disappearing message timer which may or may not be desirable
            binding.accentView.visibility = if (unreadCount > 0 && !thread.isRead) View.VISIBLE else View.INVISIBLE
        }
        val formattedUnreadCount = if (unreadCount == 0) {
            null
        } else {
            if (unreadCount < 10000) unreadCount.toString() else "9999+"
        }
        binding.unreadCountTextView.text = formattedUnreadCount
        val textSize = if (unreadCount < 1000) 12.0f else 10.0f
        binding.unreadCountTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize)
        binding.unreadCountIndicator.isVisible = (unreadCount != 0 && !thread.isRead)
                || (configFactory.convoVolatile?.getConversationUnread(thread) == true)
        binding.unreadMentionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize)
        binding.unreadMentionIndicator.isVisible = (thread.unreadMentionCount != 0 && thread.recipient.address.isGroup)
        val senderDisplayName = getTitle(thread.recipient)
                ?: thread.recipient.address.toString()
        binding.conversationViewDisplayNameTextView.text = senderDisplayName
        binding.timestampTextView.text = thread.date.takeIf { it != 0L }?.let { DateUtils.getDisplayFormattedTimeSpanString(context, Locale.getDefault(), it) }
        val recipient = thread.recipient
        binding.muteIndicatorImageView.isVisible = recipient.isMuted || recipient.notifyType != NOTIFY_TYPE_ALL
        val drawableRes = if (recipient.isMuted || recipient.notifyType == NOTIFY_TYPE_NONE) {
            R.drawable.ic_outline_notifications_off_24
        } else {
            R.drawable.ic_notifications_mentions
        }
        binding.muteIndicatorImageView.setImageResource(drawableRes)
        binding.snippetTextView.text = highlightMentions(
            text = thread.getDisplayBody(context),
            formatOnly = true, // no styling here, only text formatting
            threadID = thread.threadId,
            context = context
        )
        binding.snippetTextView.typeface = if (unreadCount > 0 && !thread.isRead) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        binding.snippetTextView.visibility = if (isTyping) View.GONE else View.VISIBLE
        if (isTyping) {
            binding.typingIndicatorView.root.startAnimation()
        } else {
            binding.typingIndicatorView.root.stopAnimation()
        }
        binding.typingIndicatorView.root.visibility = if (isTyping) View.VISIBLE else View.GONE
        binding.statusIndicatorImageView.visibility = View.VISIBLE
        when {
            !thread.isOutgoing -> binding.statusIndicatorImageView.visibility = View.GONE
            thread.isFailed -> {
                val drawable = ContextCompat.getDrawable(context, R.drawable.ic_error)?.mutate()
                drawable?.setTint(ThemeUtil.getThemedColor(context, R.attr.danger))
                binding.statusIndicatorImageView.setImageDrawable(drawable)
            }
            thread.isPending -> binding.statusIndicatorImageView.setImageResource(R.drawable.ic_circle_dot_dot_dot)
            thread.isRead -> binding.statusIndicatorImageView.setImageResource(R.drawable.ic_filled_circle_check)
            else -> binding.statusIndicatorImageView.setImageResource(R.drawable.ic_circle_check)
        }
        binding.profilePictureView.update(thread.recipient)
    }

    fun recycle() {
        binding.profilePictureView.recycle()
    }

    private fun getTitle(recipient: Recipient): String? = when {
        recipient.isLocalNumber -> context.getString(R.string.noteToSelf)
        else -> recipient.toShortString() // Internally uses the Contact API
    }
    // endregion
}

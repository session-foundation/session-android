package org.thoughtcrime.securesms.messagerequests

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewMessageRequestBinding
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities.highlightMentions
import org.thoughtcrime.securesms.database.model.ThreadRecord
import com.bumptech.glide.RequestManager
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.UnreadStylingHelper
import org.thoughtcrime.securesms.util.getAccentColor
import java.util.Locale
import javax.inject.Inject

class MessageRequestView : LinearLayout {
    private lateinit var binding: ViewMessageRequestBinding
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    var thread: ThreadRecord? = null

    // region Lifecycle
    constructor(context: Context) : super(context) { initialize() }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { initialize() }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { initialize() }

    private fun initialize() {
        binding = ViewMessageRequestBinding.inflate(LayoutInflater.from(context), this, true)
        layoutParams = RecyclerView.LayoutParams(screenWidth, RecyclerView.LayoutParams.WRAP_CONTENT)
    }
    // endregion

    // region Updating
    fun bind(thread: ThreadRecord, dateUtils: DateUtils) {
        this.thread = thread

        val senderDisplayName = getUserDisplayName(thread.recipient) ?: thread.recipient.address.toString()
        val unreadCount = thread.unreadCount
        val isUnread = unreadCount > 0 && !thread.isRead

        binding.root.background = UnreadStylingHelper.getUnreadBackground(context, isUnread)

        binding.accentView.apply {
            this.background = UnreadStylingHelper.getAccentBackground(context)
            isVisible = isUnread
        }

        binding.unreadCountTextView.text = UnreadStylingHelper.formatUnreadCount(unreadCount)
        binding.unreadCountIndicator.isVisible =  isUnread

        binding.displayNameTextView.text = senderDisplayName
        binding.timestampTextView.text = dateUtils.getDisplayFormattedTimeSpanString(
            thread.date
        )

        val snippet = highlightMentions(
            text = thread.getDisplayBody(context),
            formatOnly = true, // no styling here, only text formatting
            threadID = thread.threadId,
            context = context
        )

        binding.snippetTextView.apply {
            text = snippet
            typeface = UnreadStylingHelper.getUnreadTypeface(isUnread)
        }

        post {
            binding.profilePictureView.update(thread.recipient)
        }
    }

    fun recycle() {
        binding.profilePictureView.recycle()
    }

    private fun getUserDisplayName(recipient: Recipient): String? {
        return if (recipient.isLocalNumber) {
            context.getString(R.string.noteToSelf)
        } else {
            recipient.name // Internally uses the Contact API
        }
    }
    // endregion
}

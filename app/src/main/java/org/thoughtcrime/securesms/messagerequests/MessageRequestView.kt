package org.thoughtcrime.securesms.messagerequests

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewMessageRequestBinding
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities.highlightMentions
import org.thoughtcrime.securesms.database.model.ThreadRecord
import com.bumptech.glide.RequestManager
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.UnreadStylingHelper
import org.thoughtcrime.securesms.util.getAccentColor
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MessageRequestView : LinearLayout {
    private lateinit var binding: ViewMessageRequestBinding
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    var thread: ThreadRecord? = null

    @Inject lateinit var proStatusManager: ProStatusManager

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
            visibility = if(isUnread) View.VISIBLE else View.INVISIBLE
        }

        binding.unreadCountTextView.text = UnreadStylingHelper.formatUnreadCount(unreadCount)
        binding.unreadCountIndicator.isVisible =  isUnread

        binding.displayName.apply {
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

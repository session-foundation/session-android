package org.thoughtcrime.securesms.messagerequests

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewMessageRequestBinding
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.recipients.shouldShowProBadge
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities.highlightMentions
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.UnreadStylingHelper
import javax.inject.Inject

@AndroidEntryPoint
class MessageRequestView : LinearLayout {
    private lateinit var binding: ViewMessageRequestBinding
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    var thread: ThreadRecord? = null

    @Inject
    lateinit var proStatusManager: ProStatusManager

    @Inject
    lateinit var avatarUtils: AvatarUtils

    @Inject
    lateinit var recipientRepository: RecipientRepository

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

        binding.unreadCountTextView.apply{
            text = UnreadStylingHelper.formatUnreadCount(unreadCount)
            isVisible =  isUnread
        }

        binding.displayName.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setThemedContent {
                ProBadgeText(
                    text = senderDisplayName,
                    textStyle = LocalType.current.h8.bold().copy(color = LocalColors.current.text),
                    showBadge = thread.recipient.proStatus.shouldShowProBadge()
                            && !thread.recipient.isLocalNumber,
                )
            }
        }

        binding.timestampTextView.text = dateUtils.getDisplayFormattedTimeSpanString(
            thread.date
        )

        val snippet = highlightMentions(
            recipientRepository = recipientRepository,
            text = thread.getDisplayBody(context),
            formatOnly = true, // no styling here, only text formatting
            context = context
        )

        binding.snippetTextView.apply {
            text = snippet
            typeface = UnreadStylingHelper.getUnreadTypeface(isUnread)
        }

        binding.profilePictureView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.profilePictureView.setThemedContent {
            Avatar(
                size = LocalDimensions.current.iconLarge,
                data = avatarUtils.getUIDataFromRecipient(thread.recipient)
            )
        }
    }

    fun recycle() {
    }

    private fun getUserDisplayName(recipient: Recipient): String? {
        return if (recipient.isLocalNumber) {
            context.getString(R.string.noteToSelf)
        } else {
            recipient.displayName() // Internally uses the Contact API
        }
    }
    // endregion
}

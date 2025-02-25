package org.thoughtcrime.securesms.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.FragmentConversationBottomSheetBinding
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.util.getConversationUnread
import javax.inject.Inject

@AndroidEntryPoint
class ConversationOptionsBottomSheet(private val parentContext: Context) : BottomSheetDialogFragment(), View.OnClickListener {
    private lateinit var binding: FragmentConversationBottomSheetBinding
    //FIXME AC: Supplying a threadRecord directly into the field from an activity
    // is not the best idea. It doesn't survive configuration change.
    // We should be dealing with IDs and all sorts of serializable data instead
    // if we want to use dialog fragments properly.
    lateinit var thread: ThreadRecord

    @Inject lateinit var configFactory: ConfigFactory

    var onViewDetailsTapped: (() -> Unit?)? = null
    var onCopyConversationId: (() -> Unit?)? = null
    var onPinTapped: (() -> Unit)? = null
    var onUnpinTapped: (() -> Unit)? = null
    var onBlockTapped: (() -> Unit)? = null
    var onUnblockTapped: (() -> Unit)? = null
    var onDeleteTapped: (() -> Unit)? = null
    var onMarkAllAsReadTapped: (() -> Unit)? = null
    var onNotificationTapped: (() -> Unit)? = null
    var onSetMuteTapped: ((Boolean) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentConversationBottomSheetBinding.inflate(LayoutInflater.from(parentContext), container, false)
        return binding.root
    }

    override fun onClick(v: View?) {
        when (v) {
            binding.detailsTextView -> onViewDetailsTapped?.invoke()
            binding.copyConversationId -> onCopyConversationId?.invoke()
            binding.copyCommunityUrl -> onCopyConversationId?.invoke()
            binding.pinTextView -> onPinTapped?.invoke()
            binding.unpinTextView -> onUnpinTapped?.invoke()
            binding.blockTextView -> onBlockTapped?.invoke()
            binding.unblockTextView -> onUnblockTapped?.invoke()
            binding.deleteTextView -> onDeleteTapped?.invoke()
            binding.markAllAsReadTextView -> onMarkAllAsReadTapped?.invoke()
            binding.notificationsTextView -> onNotificationTapped?.invoke()
            binding.unMuteNotificationsTextView -> onSetMuteTapped?.invoke(false)
            binding.muteNotificationsTextView -> onSetMuteTapped?.invoke(true)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!this::thread.isInitialized) { return dismiss() }
        val recipient = thread.recipient
        if (!recipient.isGroupRecipient && !recipient.isLocalNumber) {
            binding.detailsTextView.visibility = View.VISIBLE
            binding.unblockTextView.visibility = if (recipient.isBlocked) View.VISIBLE else View.GONE
            binding.blockTextView.visibility = if (recipient.isBlocked) View.GONE else View.VISIBLE
            binding.detailsTextView.setOnClickListener(this)
            binding.blockTextView.setOnClickListener(this)
            binding.unblockTextView.setOnClickListener(this)
        } else {
            binding.detailsTextView.visibility = View.GONE
        }
        binding.copyConversationId.visibility = if (!recipient.isGroupRecipient && !recipient.isLocalNumber) View.VISIBLE else View.GONE
        binding.copyConversationId.setOnClickListener(this)
        binding.copyCommunityUrl.visibility = if (recipient.isCommunityRecipient) View.VISIBLE else View.GONE
        binding.copyCommunityUrl.setOnClickListener(this)
        binding.unMuteNotificationsTextView.isVisible = recipient.isMuted && !recipient.isLocalNumber
        binding.muteNotificationsTextView.isVisible = !recipient.isMuted && !recipient.isLocalNumber
        binding.unMuteNotificationsTextView.setOnClickListener(this)
        binding.muteNotificationsTextView.setOnClickListener(this)
        binding.notificationsTextView.isVisible = recipient.isGroupRecipient && !recipient.isMuted
        binding.notificationsTextView.setOnClickListener(this)

        // delete
        binding.deleteTextView.apply {
            setOnClickListener(this@ConversationOptionsBottomSheet)

            // the text and content description will change depending on the type
            when{
                // groups and communities
                recipient.isGroupRecipient -> {
                    text = context.getString(R.string.leave)
                    contentDescription = context.getString(R.string.AccessibilityId_leave)
                }

                // note to self
                recipient.isLocalNumber -> {
                    text = context.getString(R.string.clear)
                    contentDescription = context.getString(R.string.AccessibilityId_clear)
                }

                // 1on1
                else -> {
                    text = context.getString(R.string.delete)
                    contentDescription = context.getString(R.string.AccessibilityId_delete)
                }
            }
        }

        binding.markAllAsReadTextView.isVisible = thread.unreadCount > 0 || configFactory.convoVolatile?.getConversationUnread(thread) == true
        binding.markAllAsReadTextView.setOnClickListener(this)
        binding.pinTextView.isVisible = !thread.isPinned
        binding.unpinTextView.isVisible = thread.isPinned
        binding.pinTextView.setOnClickListener(this)
        binding.unpinTextView.setOnClickListener(this)
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setDimAmount(0.6f)
    }
}
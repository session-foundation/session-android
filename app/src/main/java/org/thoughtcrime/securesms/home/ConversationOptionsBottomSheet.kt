package org.thoughtcrime.securesms.home

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.FragmentConversationBottomSheetBinding
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.ui.adaptive.getAdaptiveInfo
import javax.inject.Inject

@AndroidEntryPoint
class ConversationOptionsBottomSheet() : BottomSheetDialogFragment(), View.OnClickListener {
    private lateinit var binding: FragmentConversationBottomSheetBinding

    lateinit var publicKey: String
    lateinit var thread: ThreadRecord
    var group: GroupRecord? = null

    @Inject lateinit var configFactory: ConfigFactory
    @Inject lateinit var deprecationManager: LegacyGroupDeprecationManager

    @Inject lateinit var groupDatabase: GroupDatabase
    @Inject lateinit var loginStateRepository: LoginStateRepository

    @Inject lateinit var threadDatabase: ThreadDatabase

    var onViewDetailsTapped: (() -> Unit?)? = null
    var onCopyConversationId: (() -> Unit?)? = null
    var onPinTapped: (() -> Unit)? = null
    var onUnpinTapped: (() -> Unit)? = null
    var onBlockTapped: (() -> Unit)? = null
    var onUnblockTapped: (() -> Unit)? = null
    var onDeleteTapped: (() -> Unit)? = null
    var onMarkAllAsReadTapped: (() -> Unit)? = null
    var onMarkAsUnreadTapped : (() -> Unit)? = null
    var onNotificationTapped: (() -> Unit)? = null
    var onDeleteContactTapped: (() -> Unit)? = null


    companion object {
        const val FRAGMENT_TAG = "ConversationOptionsBottomSheet"
        private const val ARG_PUBLIC_KEY = "arg_public_key"
        const val ARG_THREAD_ID = "arg_thread_id"
        const  val ARG_ADDRESS = "arg_address"

        fun newInstance(publicKey: String, threadId: Long, address: String): ConversationOptionsBottomSheet {
            return ConversationOptionsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PUBLIC_KEY, publicKey)
                    putLong(ARG_THREAD_ID, threadId)
                    putString(ARG_ADDRESS, address)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        publicKey = requireNotNull(args.getString(ARG_PUBLIC_KEY))
        requireNotNull(args.getLong(ARG_THREAD_ID))
        val addressString = requireNotNull(args.getString(ARG_ADDRESS))
        val address = Address.fromSerialized(addressString)
        thread = requireNotNull(
            threadDatabase.getThreads(listOf(address)).firstOrNull()
        ) { "Thread not found for address: $addressString" }
        group = groupDatabase.getGroup(thread.recipient.address.toString()).orNull()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentConversationBottomSheetBinding.inflate(inflater, container, false)
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
            binding.markAsUnreadTextView -> onMarkAsUnreadTapped?.invoke()
            binding.notificationsTextView -> onNotificationTapped?.invoke()
            binding.deleteContactTextView -> onDeleteContactTapped?.invoke()
        }
    }

    private val Recipient.canBlock: Boolean get() = address is Address.Standard

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!this::thread.isInitialized) { return dismiss() }
        val recipient = thread.recipient

        binding.deleteContactTextView.isVisible = false

        if (!recipient.isGroupOrCommunityRecipient && !recipient.isLocalNumber) {
            binding.detailsTextView.visibility = View.VISIBLE
            binding.unblockTextView.visibility = if (recipient.canBlock && recipient.blocked) View.VISIBLE else View.GONE
            binding.blockTextView.visibility = if (recipient.canBlock && !recipient.blocked) View.VISIBLE else View.GONE
            binding.detailsTextView.setOnClickListener(this)
            binding.blockTextView.setOnClickListener(this)
            binding.unblockTextView.setOnClickListener(this)
        } else {
            binding.detailsTextView.visibility = View.GONE
        }

        val isDeprecatedLegacyGroup = recipient.isLegacyGroupRecipient &&
                deprecationManager.isDeprecated

        binding.copyConversationId.isVisible = !recipient.isGroupOrCommunityRecipient
                && !recipient.isLocalNumber
                && !isDeprecatedLegacyGroup

        binding.copyConversationId.setOnClickListener(this)
        binding.copyCommunityUrl.isVisible = recipient.isCommunityRecipient
        binding.copyCommunityUrl.setOnClickListener(this)

        val notificationIconRes = when{
            recipient.isMuted() -> R.drawable.ic_volume_off
            recipient.notifyType == NotifyType.MENTIONS ->
                R.drawable.ic_at_sign
            else -> R.drawable.ic_volume_2
        }
        binding.notificationsTextView.setCompoundDrawablesWithIntrinsicBounds(notificationIconRes, 0, 0, 0)
        binding.notificationsTextView.isVisible = !recipient.isLocalNumber && !isDeprecatedLegacyGroup
        binding.notificationsTextView.setOnClickListener(this)

        // delete
        binding.deleteTextView.apply {
            setOnClickListener(this@ConversationOptionsBottomSheet)

            val drawableStartRes: Int

            // the text, content description and icon will change depending on the type
            when {
                recipient.isLegacyGroupRecipient -> {
                    val group = groupDatabase.getGroup(recipient.address.toString()).orNull()

                    val isGroupAdmin = group.admins.map { it.toString() }
                        .contains(loginStateRepository.requireLocalNumber())

                    if (isGroupAdmin) {
                        text = context.getString(R.string.delete)
                        contentDescription = context.getString(R.string.AccessibilityId_delete)
                        drawableStartRes = R.drawable.ic_trash_2
                    } else {
                        text = context.getString(R.string.leave)
                        contentDescription = context.getString(R.string.AccessibilityId_leave)
                        drawableStartRes = R.drawable.ic_log_out
                    }
                }

                // groups and communities
                recipient.isGroupV2Recipient -> {
                    val accountId = AccountId(recipient.address.toString())
                    val group = configFactory.withUserConfigs { it.userGroups.getClosedGroup(accountId.hexString) } ?: return
                    // if you are in a group V2 and have been kicked of that group, or the group was destroyed,
                    // or if the user is an admin
                    // the button should read 'Delete' instead of 'Leave'
                    if (!group.shouldPoll || group.hasAdminKey()) {
                        text = context.getString(R.string.delete)
                        contentDescription = context.getString(R.string.AccessibilityId_delete)
                        drawableStartRes = R.drawable.ic_trash_2
                    } else {
                        text = context.getString(R.string.leave)
                        contentDescription = context.getString(R.string.AccessibilityId_leave)
                        drawableStartRes = R.drawable.ic_log_out
                    }
                }

                recipient.isCommunityRecipient -> {
                    text = context.getString(R.string.leave)
                    contentDescription = context.getString(R.string.AccessibilityId_leave)
                    drawableStartRes = R.drawable.ic_log_out
                }

                // note to self
                recipient.isLocalNumber -> {
                    text = context.getString(R.string.hide)
                    contentDescription = context.getString(R.string.AccessibilityId_clear)
                    drawableStartRes = R.drawable.ic_eye_off
                }

                // 1on1
                else -> {
                    text = context.getString(R.string.conversationsDelete)
                    contentDescription = context.getString(R.string.AccessibilityId_delete)
                    drawableStartRes = R.drawable.ic_trash_2

                    // also show delete contact for 1on1
                    binding.deleteContactTextView.isVisible = true
                    binding.deleteContactTextView.setOnClickListener(this@ConversationOptionsBottomSheet)
                }
            }

            TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(this, drawableStartRes, 0, 0, 0)
        }

        // We have three states for a conversation:
        // 1. The conversation has unread messages
        // 2. The conversation is marked as unread from the config (which is different from having unread messages)
        // 3. The conversation is up to date
        // Case 1 and 2 should show the 'mark as read' button while case 3 should show 'mark as unread'

        // case 1
        val hasUnreadMessages = thread.unreadCount > 0

        // case 2
        val isMarkedAsUnread = thread.isUnread

        val showMarkAsReadButton = hasUnreadMessages || isMarkedAsUnread

        binding.markAllAsReadTextView.isVisible = showMarkAsReadButton && !isDeprecatedLegacyGroup
        binding.markAllAsReadTextView.setOnClickListener(this)
        binding.markAsUnreadTextView.isVisible = !showMarkAsReadButton
                && !isDeprecatedLegacyGroup
                && recipient.address !is Address.CommunityBlindedId
        binding.markAsUnreadTextView.setOnClickListener(this)
        binding.pinTextView.isVisible = !thread.isPinned && !isDeprecatedLegacyGroup
                && recipient.address !is Address.CommunityBlindedId
        binding.unpinTextView.isVisible = thread.isPinned
                && recipient.address !is Address.CommunityBlindedId
        binding.pinTextView.setOnClickListener(this)
        binding.unpinTextView.setOnClickListener(this)
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setDimAmount(0.6f)

        val dlg = dialog as? BottomSheetDialog ?: return
        val sheet = dlg.findViewById<android.widget.FrameLayout>(R.id.design_bottom_sheet)
            ?: return

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE){
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }

        ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
            val cut = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )

            binding.root.updatePadding(left = cut.left, right = cut.right)
            insets
        }

        ViewCompat.requestApplyInsets(sheet)
    }
}
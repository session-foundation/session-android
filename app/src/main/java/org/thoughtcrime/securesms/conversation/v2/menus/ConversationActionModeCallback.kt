package org.thoughtcrime.securesms.conversation.v2.menus

import android.content.Context
import android.view.ActionMode
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import network.loki.messenger.R
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.v2.ConversationAdapter
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.groups.OpenGroupManager
import androidx.core.view.size
import androidx.core.view.get
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import org.session.libsignal.utilities.Hex

class ConversationActionModeCallback(
    private val adapter: ConversationAdapter,
    private val threadID: Long,
    private val context: Context,
    private val deprecationManager: LegacyGroupDeprecationManager,
    private val openGroupManager: OpenGroupManager,
    ) : ActionMode.Callback {
    var delegate: ConversationActionModeCallbackDelegate? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val themedContext = ContextThemeWrapper(context, context.theme)
        val inflater = MenuInflater(themedContext)
        inflater.inflate(R.menu.menu_conversation_item_action, menu)
        updateActionModeMenu(menu)

        // tint icons manually as it seems the xml color is ignored, in spite of the context theme wrapper
        val tintColor = context.getColorFromAttr(android.R.attr.textColorPrimary)

        for (i in 0 until menu.size) {
            val menuItem = menu[i]
            menuItem.icon?.setTint(tintColor)
        }

        return true
    }

    fun updateActionModeMenu(menu: Menu) {
        // Prepare
        val selectedItems = adapter.selectedItems
        val containsControlMessage = selectedItems.any { it.isControlMessage }
        val hasText = selectedItems.any { it.body.isNotEmpty() }
        if (selectedItems.isEmpty()) { return }
        val firstMessage = selectedItems.iterator().next()
        val openGroup = DatabaseComponent.get(context).lokiThreadDatabase().getOpenGroupChat(threadID)
        val thread = DatabaseComponent.get(context).threadDatabase().getRecipientForThreadId(threadID)!!
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)!!
        val edKeyPair = MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair()!!
        val blindedPublicKey = openGroup?.publicKey?.let {
            BlindKeyAPI.blind15KeyPairOrNull(
                ed25519SecretKey = edKeyPair.secretKey.data,
                serverPubKey = Hex.fromStringCondensed(it),
            )?.pubKey?.data }
            ?.let { AccountId(IdPrefix.BLINDED, it) }?.hexString

        val isDeprecatedLegacyGroup = thread.isLegacyGroupRecipient &&
                deprecationManager.isDeprecated

        // Embedded function
        fun userCanBanSelectedUsers(): Boolean {
            if (openGroup == null) { return false }
            val anySentByCurrentUser = selectedItems.any { it.isOutgoing }
            if (anySentByCurrentUser) { return false } // Users can't ban themselves
            val selectedUsers = selectedItems.map { it.recipient.address.toString() }.toSet()
            if (selectedUsers.size > 1) { return false }
            return openGroupManager.isUserModerator(
                openGroup.groupId,
                userPublicKey,
                blindedPublicKey
            )
        }


        // Delete message
        menu.findItem(R.id.menu_context_delete_message).isVisible = !isDeprecatedLegacyGroup // can always delete since delete logic will be handled by the VM
        // Ban user
        menu.findItem(R.id.menu_context_ban_user).isVisible = userCanBanSelectedUsers() && !isDeprecatedLegacyGroup
        // Ban and delete all
        menu.findItem(R.id.menu_context_ban_and_delete_all).isVisible = userCanBanSelectedUsers() && !isDeprecatedLegacyGroup
        // Copy message text
        menu.findItem(R.id.menu_context_copy).isVisible = !containsControlMessage && hasText
        // Copy Account ID
        menu.findItem(R.id.menu_context_copy_public_key).isVisible =
             (thread.isGroupOrCommunityRecipient && !thread.isCommunityRecipient && selectedItems.size == 1 && firstMessage.individualRecipient.address.toString() != userPublicKey)
        // Message detail
        menu.findItem(R.id.menu_message_details).isVisible = selectedItems.size == 1 && !isDeprecatedLegacyGroup
        // Resend
        menu.findItem(R.id.menu_context_resend).isVisible = (selectedItems.size == 1 && firstMessage.isFailed) && !isDeprecatedLegacyGroup
        // Resync
        menu.findItem(R.id.menu_context_resync).isVisible = (selectedItems.size == 1 && firstMessage.isSyncFailed) && !isDeprecatedLegacyGroup
        // Save media
        menu.findItem(R.id.menu_context_save_attachment).isVisible = (selectedItems.size == 1
            && firstMessage.isMms && (firstMessage as MediaMmsMessageRecord).containsMediaSlide())
        // Reply
        menu.findItem(R.id.menu_context_reply).isVisible =
            (!isDeprecatedLegacyGroup && selectedItems.size == 1 && !firstMessage.isPending && !firstMessage.isFailed && !firstMessage.isOpenGroupInvitation)
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val selectedItems = adapter.selectedItems.toSet()
        when (item.itemId) {
            R.id.menu_context_delete_message -> delegate?.deleteMessages(selectedItems)
            R.id.menu_context_ban_user -> delegate?.banUser(selectedItems)
            R.id.menu_context_ban_and_delete_all -> delegate?.banAndDeleteAll(selectedItems)
            R.id.menu_context_copy -> delegate?.copyMessages(selectedItems)
            R.id.menu_context_resync -> delegate?.resyncMessage(selectedItems)
            R.id.menu_context_resend -> delegate?.resendMessage(selectedItems)
            R.id.menu_message_details -> delegate?.showMessageDetail(selectedItems)
            R.id.menu_context_save_attachment -> delegate?.saveAttachmentsIfPossible(selectedItems)
            R.id.menu_context_reply -> delegate?.reply(selectedItems)
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        adapter.selectedItems.clear()
        adapter.notifyDataSetChanged()
        delegate?.destroyActionMode()
    }
}

interface ConversationActionModeCallbackDelegate {

    fun selectMessages(messages: Set<MessageRecord>)
    fun deleteMessages(messages: Set<MessageRecord>)
    fun banUser(messages: Set<MessageRecord>)
    fun banAndDeleteAll(messages: Set<MessageRecord>)
    fun copyMessages(messages: Set<MessageRecord>)
    fun resyncMessage(messages: Set<MessageRecord>)
    fun resendMessage(messages: Set<MessageRecord>)
    fun showMessageDetail(messages: Set<MessageRecord>)
    fun saveAttachmentsIfPossible(messages: Set<MessageRecord>)
    fun reply(messages: Set<MessageRecord>)
    fun destroyActionMode()
}
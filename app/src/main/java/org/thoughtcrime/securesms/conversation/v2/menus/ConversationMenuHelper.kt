package org.thoughtcrime.securesms.conversation.v2.menus

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.AsyncTask
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.squareup.phrase.Phrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.sending_receiving.leave
import org.session.libsession.utilities.GroupUtil.doubleDecodeGroupID
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.wasKickedFromGroupV2
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.ShortcutLauncherActivity
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.groups.legacy.EditLegacyGroupActivity
import org.thoughtcrime.securesms.groups.legacy.EditLegacyGroupActivity.Companion.groupIDKey
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.util.BitmapUtil
import java.io.IOException

object ConversationMenuHelper {

    fun onPrepareOptionsMenu(
        menu: Menu,
        inflater: MenuInflater,
        thread: Recipient,
        context: Context,
        configFactory: ConfigFactory,
        deprecationManager: LegacyGroupDeprecationManager,
    ) {
        val isDeprecatedLegacyGroup = thread.isLegacyGroupRecipient &&
                deprecationManager.isDeprecated

        // Prepare
        menu.clear()
        val isCommunity = thread.isCommunityRecipient
        // Base menu (options that should always be present)
        inflater.inflate(R.menu.menu_conversation, menu)
        menu.findItem(R.id.menu_add_shortcut).isVisible = !isDeprecatedLegacyGroup

        // Expiring messages
        if (!isCommunity && (thread.hasApprovedMe() || thread.isLegacyGroupRecipient || thread.isLocalNumber)
            && !isDeprecatedLegacyGroup) {
            inflater.inflate(R.menu.menu_conversation_expiration, menu)
        }
        // One-on-one chat menu allows copying the account id
        if (thread.isContactRecipient) {
            inflater.inflate(R.menu.menu_conversation_copy_account_id, menu)
        }
        // One-on-one chat menu (options that should only be present for one-on-one chats)
       /* if (thread.isContactRecipient) {
            if (thread.isBlocked) {
                inflater.inflate(R.menu.menu_conversation_unblock, menu)
            } else if (!thread.isLocalNumber) {
                inflater.inflate(R.menu.menu_conversation_block, menu)
            }
        }*/
        // (Legacy) Closed group menu (options that should only be present in closed groups)
        if (thread.isLegacyGroupRecipient) {
            inflater.inflate(R.menu.menu_conversation_legacy_group, menu)

            menu.findItem(R.id.menu_edit_group).isVisible = !isDeprecatedLegacyGroup
        }

        // Groups v2 menu
        if (thread.isGroupV2Recipient) {
            val hasAdminKey = configFactory.withUserConfigs { it.userGroups.getClosedGroup(thread.address.toString())?.hasAdminKey() }
            if (hasAdminKey == true) {
                inflater.inflate(R.menu.menu_conversation_groups_v2_admin, menu)
            } else {
                inflater.inflate(R.menu.menu_conversation_groups_v2, menu)
            }

            // If the current user was kicked from the group
            // the menu should say 'Delete' instead of 'Leave'
            if (configFactory.wasKickedFromGroupV2(thread)) {
                menu.findItem(R.id.menu_leave_group).title = context.getString(R.string.groupDelete)
            }
        }

        // Open group menu
        if (isCommunity) {
            inflater.inflate(R.menu.menu_conversation_open_group, menu)
        }
        // Muting
        if (!isDeprecatedLegacyGroup) {
            if (thread.isMuted) {
                inflater.inflate(R.menu.menu_conversation_muted, menu)
            } else {
                inflater.inflate(R.menu.menu_conversation_unmuted, menu)
            }
        }

        if (thread.isGroupOrCommunityRecipient && !thread.isMuted && !isDeprecatedLegacyGroup) {
            inflater.inflate(R.menu.menu_conversation_notification_settings, menu)
        }

        /*if (thread.showCallMenu()) {
            inflater.inflate(R.menu.menu_conversation_call, menu)
        }*/

        // Search
        /*val searchViewItem = menu.findItem(R.id.menu_search)
        (context as ConversationActivityV2).searchViewItem = searchViewItem
        val searchView = searchViewItem.actionView as SearchView
        val queryListener = object : OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                return true
            }

            override fun onQueryTextChange(query: String): Boolean {
                context.onSearchQueryUpdated(query)
                return true
            }
        }
        searchViewItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchView.setOnQueryTextListener(queryListener)
                context.onSearchOpened()
                for (i in 0 until menu.size()) {
                    if (menu.getItem(i) != searchViewItem) {
                        menu.getItem(i).isVisible = false
                    }
                }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchView.setOnQueryTextListener(null)
                context.onSearchClosed()
                return true
            }
        })*/
    }

    /**
     * Handle the selected option
     *
     * @return An asynchronous channel that can be used to wait for the action to complete. Null if
     * the action does not require waiting.
     */
    fun onOptionItemSelected(
        context: Context,
        item: MenuItem,
        thread: Recipient,
        threadID: Long,
        factory: ConfigFactory,
        storage: StorageProtocol,
        groupManager: GroupManagerV2,
        deprecationManager: LegacyGroupDeprecationManager,
    ): ReceiveChannel<GroupLeavingStatus>? {
        when (item.itemId) {
            R.id.menu_view_all_media -> { showAllMedia(context, thread) }
         //   R.id.menu_search -> { search(context) }
            R.id.menu_add_shortcut -> { addShortcut(context, thread) }
            //R.id.menu_expiring_messages -> { showDisappearingMessages(context, thread) }
           /* R.id.menu_unblock -> { unblock(context, thread) }
            R.id.menu_block -> { block(context, thread, deleteThread = false) }*/
            R.id.menu_copy_account_id -> { copyAccountID(context, thread) }
            R.id.menu_copy_open_group_url -> { copyOpenGroupUrl(context, thread) }
            R.id.menu_edit_group -> { editGroup(context, thread) }
            R.id.menu_group_members -> { showGroupMembers(context, thread) }
           /* R.id.menu_leave_group -> { return leaveGroup(
                context, thread, threadID, factory, storage, groupManager, deprecationManager
            ) }*/
//            R.id.menu_invite_to_open_group -> { inviteContacts(context, thread) }
//            R.id.menu_unmute_notifications -> { unmute(context, thread) }
//            R.id.menu_mute_notifications -> { mute(context, thread) }
//            R.id.menu_notification_settings -> { setNotifyType(context, thread) }
        }

        return null
    }

    private fun showAllMedia(context: Context, thread: Recipient) {
//        val activity = context as AppCompatActivity
//        activity.startActivity(MediaOverviewActivity.createIntent(context, thread.address))
    }

//    private fun search(context: Context) {
//        val searchViewModel = (context as ConversationActivityV2).searchViewModel
//        searchViewModel.onSearchOpened()
//    }

    @SuppressLint("StaticFieldLeak")
    private fun addShortcut(context: Context, thread: Recipient) {
        object : AsyncTask<Void?, Void?, IconCompat?>() {

            @Deprecated("Deprecated in Java")
            override fun doInBackground(vararg params: Void?): IconCompat? {
                var icon: IconCompat? = null
                val contactPhoto = thread.contactPhoto
                if (contactPhoto != null) {
                    try {
                        var bitmap = BitmapFactory.decodeStream(contactPhoto.openInputStream(context))
                        bitmap = BitmapUtil.createScaledBitmap(bitmap, 300, 300)
                        icon = IconCompat.createWithAdaptiveBitmap(bitmap)
                    } catch (e: IOException) {
                        // Do nothing
                    }
                }
                if (icon == null) {
                    icon = IconCompat.createWithResource(context, if (thread.isGroupOrCommunityRecipient) R.mipmap.ic_group_shortcut else R.mipmap.ic_person_shortcut)
                }
                return icon
            }

            @Deprecated("Deprecated in Java")
            override fun onPostExecute(icon: IconCompat?) {
                val name = Optional.fromNullable<String>(thread.name)
                    .or(Optional.fromNullable<String>(thread.profileName))
                    .or(thread.name)

                val shortcutInfo = ShortcutInfoCompat.Builder(context, thread.address.toString() + '-' + System.currentTimeMillis())
                    .setShortLabel(name)
                    .setIcon(icon)
                    .setIntent(ShortcutLauncherActivity.createIntent(context, thread.address))
                    .build()
                if (ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)) {
                    Toast.makeText(context, context.resources.getString(R.string.conversationsAddedToHome), Toast.LENGTH_LONG).show()
                }
            }
        }.execute()
    }

   /* private fun showDisappearingMessages(context: Context, thread: Recipient) {
        val listener = context as? ConversationMenuListener ?: return
        listener.showDisappearingMessages(thread)
    }*/

 /*   private fun unblock(context: Context, thread: Recipient) {
        if (!thread.isContactRecipient) { return }
        val listener = context as? ConversationMenuListener ?: return
        listener.unblock()
    }

    private fun block(context: Context, thread: Recipient, deleteThread: Boolean) {
        if (!thread.isContactRecipient) { return }
        val listener = context as? ConversationMenuListener ?: return
        listener.block(deleteThread)
    }*/

    private fun copyAccountID(context: Context, thread: Recipient) {
        if (!thread.isContactRecipient) { return }
        val listener = context as? ConversationMenuListener ?: return
        listener.copyAccountID(thread.address.toString())
    }

    private fun copyOpenGroupUrl(context: Context, thread: Recipient) {
        if (!thread.isCommunityRecipient) { return }
        val listener = context as? ConversationMenuListener ?: return
        listener.copyOpenGroupUrl(thread)
    }

    private fun editGroup(context: Context, thread: Recipient) {
        when {
            thread.isGroupV2Recipient -> {
                //context.startActivity(EditGroupActivity.createIntent(context, thread.address.toString()))
            }

            thread.isLegacyGroupRecipient -> {
                val intent = Intent(context, EditLegacyGroupActivity::class.java)
                val groupID: String = thread.address.toGroupString()
                intent.putExtra(groupIDKey, groupID)
                context.startActivity(intent)
            }
        }
    }


    private fun showGroupMembers(context: Context, thread: Recipient) {
       // context.startActivity(GroupMembersActivity.createIntent(context, thread.address.toString()))
    }

    enum class GroupLeavingStatus {
        Leaving,
        Left,
        Error,
    }

    fun leaveGroup(
        context: Context,
        thread: Recipient,
        threadID: Long,
        configFactory: ConfigFactory,
        storage: StorageProtocol,
        groupManager: GroupManagerV2,
        deprecationManager: LegacyGroupDeprecationManager,
    ): ReceiveChannel<GroupLeavingStatus>? {
        val channel = Channel<GroupLeavingStatus>()

        when {
            thread.isLegacyGroupRecipient -> {
                val group = DatabaseComponent.get(context).groupDatabase().getGroup(thread.address.toGroupString()).orNull()

                // we do not want admin related messaging once legacy groups are deprecated
                val isGroupAdmin = if(deprecationManager.isDeprecated){
                    false
                } else { // prior to the deprecated state, calculate admin rights properly
                    val admins = group.admins
                    val accountID = TextSecurePreferences.getLocalNumber(context)
                    admins.any { it.toString() == accountID }
                }

                confirmAndLeaveGroup(
                    context = context,
                    groupName = group.title,
                    isAdmin = isGroupAdmin,
                    isKicked = false,
                    isDestroyed = false,
                    threadID = threadID,
                    storage = storage,
                    doLeave = {
                        val groupPublicKey = doubleDecodeGroupID(thread.address.toString()).toHexString()

                        check(DatabaseComponent.get(context).lokiAPIDatabase().isClosedGroup(groupPublicKey)) {
                            "Invalid group public key"
                        }
                        try {
                            channel.trySend(GroupLeavingStatus.Leaving)
                            MessageSender.leave(groupPublicKey)
                            channel.trySend(GroupLeavingStatus.Left)
                        } catch (e: Exception) {
                            channel.trySend(GroupLeavingStatus.Error)
                            throw e
                        }
                    }
                )
            }

            thread.isGroupV2Recipient -> {
                val accountId = AccountId(thread.address.toString())
                val group = configFactory.withUserConfigs { it.userGroups.getClosedGroup(accountId.hexString) } ?: return null
                val name = configFactory.withGroupConfigs(accountId) {
                    it.groupInfo.getName()
                } ?: group.name

                confirmAndLeaveGroup(
                    context = context,
                    groupName = name,
                    isAdmin = group.hasAdminKey(),
                    isKicked = configFactory.wasKickedFromGroupV2(thread),
                    isDestroyed = group.destroyed,
                    threadID = threadID,
                    storage = storage,
                    doLeave = {
                        try {
                            channel.trySend(GroupLeavingStatus.Leaving)
                            groupManager.leaveGroup(accountId)
                            channel.trySend(GroupLeavingStatus.Left)
                        } catch (e: Exception) {
                            channel.trySend(GroupLeavingStatus.Error)
                            throw e
                        }
                    }
                )

                return channel
            }
        }

        return null
    }

    private fun confirmAndLeaveGroup(
        context: Context,
        groupName: String,
        isAdmin: Boolean,
        isKicked: Boolean,
        isDestroyed: Boolean,
        threadID: Long,
        storage: StorageProtocol,
        doLeave: suspend () -> Unit,
    ) {
        var title = R.string.groupLeave
        var message: CharSequence = ""
        var positiveButton = R.string.leave

        if(isKicked || isDestroyed){
            message = Phrase.from(context, R.string.groupDeleteDescriptionMember)
                .put(GROUP_NAME_KEY, groupName)
                .format()

            title = R.string.groupDelete
            positiveButton = R.string.delete
        } else if (isAdmin) {
            message = Phrase.from(context, R.string.groupLeaveDescriptionAdmin)
                .put(GROUP_NAME_KEY, groupName)
                .format()
        } else {
            message = Phrase.from(context, R.string.groupLeaveDescription)
                .put(GROUP_NAME_KEY, groupName)
                .format()
        }

        fun onLeaveFailed() {
            val txt = Phrase.from(context, R.string.groupLeaveErrorFailed)
                .put(GROUP_NAME_KEY, groupName)
                .format().toString()
            Toast.makeText(context, txt, Toast.LENGTH_LONG).show()
        }

        context.showSessionDialog {
            title(title)
            text(message)
            dangerButton(positiveButton) {
                GlobalScope.launch(Dispatchers.Default) {
                    try {
                        // Cancel any outstanding jobs
                        storage.cancelPendingMessageSendJobs(threadID)

                        doLeave()
                    } catch (e: Exception) {
                        Log.e("Conversation", "Error leaving group", e)
                        withContext(Dispatchers.Main) {
                            onLeaveFailed()
                        }
                    }
                }

            }
            button(R.string.cancel)
        }
    }

/*    private fun unmute(context: Context, thread: Recipient) {
        DatabaseComponent.get(context).recipientDatabase().setMuted(thread, 0)
    }

    private fun mute(context: Context, thread: Recipient) {
        showMuteDialog(ContextThemeWrapper(context, context.theme)) { until: Long ->
            DatabaseComponent.get(context).recipientDatabase().setMuted(thread, until)
        }
    }

    private fun setNotifyType(context: Context, thread: Recipient) {
        NotificationUtils.showNotifyDialog(context, thread) { notifyType ->
            DatabaseComponent.get(context).recipientDatabase().setNotifyType(thread, notifyType)
        }
    }*/

    interface ConversationMenuListener {
        fun copyAccountID(accountId: String)
        fun copyOpenGroupUrl(thread: Recipient)
    }

}
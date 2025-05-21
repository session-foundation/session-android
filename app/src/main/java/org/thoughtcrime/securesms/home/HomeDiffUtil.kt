package org.thoughtcrime.securesms.home

import android.content.Context
import androidx.recyclerview.widget.DiffUtil
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.util.getConversationUnread

class HomeDiffUtil(
        private val old: HomeViewModel.Data,
        private val new: HomeViewModel.Data,
        private val context: Context,
        private val configFactory: ConfigFactory
): DiffUtil.Callback() {

    override fun getOldListSize(): Int = old.items.size

    override fun getNewListSize(): Int = new.items.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = old.items[oldItemPosition]
        val newItem = new.items[newItemPosition]

        if (oldItem is HomeViewModel.Item.MessageRequests && newItem is HomeViewModel.Item.MessageRequests) {
            return true
        }

        if (oldItem is HomeViewModel.Item.Thread && newItem is HomeViewModel.Item.Thread) {
            return oldItem.thread.threadId == newItem.thread.threadId
        }

        return false
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val old = old.items[oldItemPosition]
        val new = new.items[newItemPosition]

        // If both are message requests, we can compare them directly
        if (old is HomeViewModel.Item.MessageRequests && new is HomeViewModel.Item.MessageRequests) {
            return old.count == new.count
        }

        // If one of the items is not a thread, we can't compare them, it's always false
        if (old !is HomeViewModel.Item.Thread || new !is HomeViewModel.Item.Thread) {
            return false
        }

        // When we reach this point, we know that both items are threads
        val oldItem = old.thread
        val newItem = new.thread

        // return early to save getDisplayBody or expensive calls
        var isSameItem = true

        if (isSameItem) { isSameItem = (oldItem.count == newItem.count) }
        if (isSameItem) { isSameItem = (oldItem.unreadCount == newItem.unreadCount) }
        if (isSameItem) { isSameItem = (oldItem.isPinned == newItem.isPinned) }
        if (isSameItem) { isSameItem = (oldItem.isRead == newItem.isRead) }

        // The recipient is passed as a reference and changes to recipients update the reference so we
        // need to cache the hashCode for the recipient and use that for diffing - unfortunately
        // recipient data is also loaded asyncronously which means every thread will refresh at least
        // once when the initial recipient data is loaded
        if (isSameItem) { isSameItem = (oldItem.initialRecipientHash == newItem.initialRecipientHash) }

        // Note: Two instances of 'SpannableString' may not equate even though their content matches
        if (isSameItem) { isSameItem = (oldItem.getDisplayBody(context).toString() == newItem.getDisplayBody(context).toString()) }

        if (isSameItem) {
            isSameItem = (
                oldItem.isFailed == newItem.isFailed &&
                oldItem.isDelivered == newItem.isDelivered &&
                oldItem.isSent == newItem.isSent &&
                oldItem.isPending == newItem.isPending &&
                oldItem.lastSeen == newItem.lastSeen &&
                !configFactory.withUserConfigs { it.convoInfoVolatile.getConversationUnread(newItem) } &&
                old.isTyping == new.isTyping
            )
        }

        return isSameItem
    }

}
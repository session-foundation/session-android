package org.thoughtcrime.securesms.messagerequests

import android.os.Build
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import network.loki.messenger.R
import org.session.libsession.utilities.ThemeUtil
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.util.DateUtils

class MessageRequestsAdapter(
    val dateUtils: DateUtils,
    val listener: ConversationClickListener
) : RecyclerView.Adapter<MessageRequestsAdapter.ViewHolder>() {
    var conversations: List<ThreadRecord> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    class ViewHolder(val view: MessageRequestView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = MessageRequestView(parent.context)
        view.setOnClickListener { view.thread?.let { listener.onConversationClick(it) } }
        view.setOnLongClickListener {
            view.thread?.let { thread ->
                showPopupMenu(view,
                    thread.recipient.isLegacyGroupRecipient
                            || thread.recipient.isCommunityRecipient,
                    thread.invitingAdminId)
            }
            true
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        holder.view.bind(conversations[position], dateUtils)
    }

    override fun getItemCount(): Int = conversations.size

    override fun onViewRecycled(holder: ViewHolder) {
        holder.view.recycle()
    }

    override fun getItemId(position: Int): Long {
        return conversations[position].threadId
    }

    private fun showPopupMenu(view: MessageRequestView, legacyOrCommunityGroup: Boolean, invitingAdmin: String?) {
        val popupMenu = PopupMenu(ContextThemeWrapper(view.context, R.style.PopupMenu_MessageRequests), view)
        // still show the block option if we have an inviting admin for the group
        if ((legacyOrCommunityGroup && invitingAdmin == null) || view.thread!!.recipient.isCommunityInboxRecipient) {
            popupMenu.menuInflater.inflate(R.menu.menu_group_request, popupMenu.menu)
        } else {
            popupMenu.menuInflater.inflate(R.menu.menu_message_request, popupMenu.menu)
        }
        popupMenu.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.menu_delete_message_request) {
                listener.onDeleteConversationClick(view.thread!!)
            } else if (menuItem.itemId == R.id.menu_block_message_request) {
                listener.onBlockConversationClick(view.thread!!)
            }
            true
        }
        for (i in 0 until popupMenu.menu.size()) {
            val item = popupMenu.menu.getItem(i)
            val s = SpannableString(item.title)
            val danger = ThemeUtil.getThemedColor(view.context, R.attr.danger)
            s.setSpan(ForegroundColorSpan(danger), 0, s.length, 0)
            item.icon?.let {
                DrawableCompat.setTint(
                    it,
                    danger
                )
            }
            item.title = s
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popupMenu.setForceShowIcon(true)
        }
        popupMenu.show()
    }
}

interface ConversationClickListener {
    fun onConversationClick(thread: ThreadRecord)
    fun onBlockConversationClick(thread: ThreadRecord)
    fun onDeleteConversationClick(thread: ThreadRecord)
}

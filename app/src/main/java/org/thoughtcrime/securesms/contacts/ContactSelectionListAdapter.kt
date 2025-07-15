package org.thoughtcrime.securesms.contacts

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import org.session.libsession.utilities.recipients.Recipient

class ContactSelectionListAdapter(private val context: Context, private val multiSelect: Boolean) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    lateinit var glide: RequestManager
    val selectedContacts = mutableSetOf<Recipient>()
    var items = listOf<Recipient>()
        set(value) { field = value; notifyDataSetChanged() }
    var contactClickListener: ContactClickListener? = null

    private object ViewType {
        const val Contact = 0
    }

    class UserViewHolder(val view: UserView) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int {
        return items.size
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is UserViewHolder) {
            holder.view.unbind()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return ViewType.Contact
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return UserViewHolder(UserView(context))
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (viewHolder is UserViewHolder) {
            viewHolder.view.setOnClickListener { contactClickListener?.onContactClick(item) }
            val isSelected = selectedContacts.contains(item)
            viewHolder.view.bind(
                item,
                if (multiSelect) UserView.ActionIndicator.Tick else UserView.ActionIndicator.None,
                isSelected,
                showCurrentUserAsNoteToSelf = true
            )
        }
    }

    fun onContactClick(recipient: Recipient) {
        if (selectedContacts.contains(recipient)) {
            selectedContacts.remove(recipient)
            contactClickListener?.onContactDeselected(recipient)
        } else if (multiSelect || selectedContacts.isEmpty()) {
            selectedContacts.add(recipient)
            contactClickListener?.onContactSelected(recipient)
        }
        val index = items.indexOf(recipient)
        notifyItemChanged(index)
    }
}

interface ContactClickListener {
    fun onContactClick(contact: Recipient)
    fun onContactSelected(contact: Recipient)
    fun onContactDeselected(contact: Recipient)
}
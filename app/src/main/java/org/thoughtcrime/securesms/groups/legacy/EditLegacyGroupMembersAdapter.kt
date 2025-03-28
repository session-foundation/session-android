package org.thoughtcrime.securesms.groups.legacy

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.contacts.UserView
import com.bumptech.glide.RequestManager
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.TextSecurePreferences

class EditLegacyGroupMembersAdapter(
    private val context: Context,
    private val glide: RequestManager,
    private val admin: Boolean,
    private val checkIsAdmin: (String) -> Boolean,
    private val memberClickListener: ((String) -> Unit)? = null
) : RecyclerView.Adapter<EditLegacyGroupMembersAdapter.ViewHolder>() {

    private val members = ArrayList<String>()
    private val zombieMembers = ArrayList<String>()

    fun setMembers(members: Collection<String>) {
        this.members.clear()
        this.members.addAll(members)
        notifyDataSetChanged()
    }

    fun setZombieMembers(members: Collection<String>) {
        this.zombieMembers.clear()
        this.zombieMembers.addAll(members)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = members.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = UserView(context)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val member = members[position]

        val unlocked = admin && member != TextSecurePreferences.getLocalNumber(context)

        viewHolder.view.bind(Recipient.from(
            context,
            Address.fromSerialized(member), false),
            glide,
            if (unlocked) UserView.ActionIndicator.Menu else UserView.ActionIndicator.None)

        if (zombieMembers.contains(member))
            viewHolder.view.alpha = 0.5F
        else
            viewHolder.view.alpha = 1F

        if (unlocked) {
            viewHolder.view.setOnClickListener { this.memberClickListener?.invoke(member) }
        } else {
            viewHolder.view.setOnClickListener(null)
        }

        viewHolder.view.handleAdminStatus(checkIsAdmin(member))
    }

    class ViewHolder(val view: UserView) : RecyclerView.ViewHolder(view)
}
package org.thoughtcrime.securesms.home.search

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.get
import androidx.core.view.size
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.security.InvalidParameterException
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewGlobalSearchHeaderBinding
import network.loki.messenger.databinding.ViewGlobalSearchResultBinding
import network.loki.messenger.databinding.ViewGlobalSearchSubheaderBinding
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.contacts.Contact as ContactModel
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.ThemeUtil
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.search.model.MessageResult
import org.thoughtcrime.securesms.ui.GetString

class GlobalSearchAdapter(
    val context: Context,
    val threadDB: ThreadDatabase,
    private val modelCallback: (Model) -> Unit
): RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val HEADER_VIEW_TYPE = 0
        const val SUB_HEADER_VIEW_TYPE = 1
        const val CONTENT_VIEW_TYPE = 2
    }

    private var data: List<Model> = listOf()
    private var query: String? = null

    fun setNewData(data: Pair<String, List<Model>>) = setNewData(data.first, data.second)

    fun setNewData(query: String, newData: List<Model>) {
        val diffResult = DiffUtil.calculateDiff(GlobalSearchDiff(this.query, query, data, newData))
        this.query = query
        data = newData
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int =
        when (data[position]) {
            is Model.Header -> HEADER_VIEW_TYPE
            is Model.SubHeader -> SUB_HEADER_VIEW_TYPE
            else -> CONTENT_VIEW_TYPE
        }

    override fun getItemCount(): Int = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            HEADER_VIEW_TYPE -> HeaderView(
                LayoutInflater.from(parent.context).inflate(R.layout.view_global_search_header, parent, false)
            )
            SUB_HEADER_VIEW_TYPE -> SubHeaderView(
                LayoutInflater.from(parent.context).inflate(R.layout.view_global_search_subheader, parent, false)
            )
            else -> ContentView(
                        LayoutInflater.from(parent.context).inflate(R.layout.view_global_search_result, parent, false),
                        modelCallback
                    )
        }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val newUpdateQuery: String? = payloads.firstOrNull { it is String } as String?
        if (newUpdateQuery != null && holder is ContentView) {
            holder.bindPayload(newUpdateQuery, data[position])
            return
        }
        when (holder) {
            is HeaderView    -> holder.bind(data[position] as Model.Header)
            is SubHeaderView -> holder.bind(data[position] as Model.SubHeader)
            is ContentView   -> holder.bind(query.orEmpty(), data[position])
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder,position, mutableListOf())
    }

    class HeaderView(view: View) : RecyclerView.ViewHolder(view) {
        val binding = ViewGlobalSearchHeaderBinding.bind(view)
        fun bind(header: Model.Header) {
            binding.searchHeader.text = header.title.string(binding.root.context)
        }
    }

    class SubHeaderView(view: View) : RecyclerView.ViewHolder(view) {
        val binding = ViewGlobalSearchSubheaderBinding.bind(view)
        fun bind(header: Model.SubHeader) {
            binding.searchHeader.text = header.title.string(binding.root.context)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ContentView) {
            holder.binding.searchResultProfilePicture.recycle()
        }
    }

    // Note: We mark the ContentView as an inner class in order to access the context from the GlobalSearchAdapter
    inner class ContentView(view: View, private val modelCallback: (Model) -> Unit) : RecyclerView.ViewHolder(view) {

        val binding = ViewGlobalSearchResultBinding.bind(view)

        fun bindPayload(newQuery: String, model: Model) {
            bindQuery(newQuery, model)
        }

        // Pop-up menu drawables do not show by default - we have to force them
        @SuppressLint("RestrictedApi")
        private fun PopupMenu.forceShowIcons() {
            try {
                val fields = this.javaClass.declaredFields
                for (field in fields) {
                    if ("mPopup" == field.name) {
                        field.isAccessible = true
                        val menuPopupHelper = field.get(this) as MenuPopupHelper
                        val classPopupHelper = Class.forName(menuPopupHelper.javaClass.name)
                        val setForceIconsMethod = classPopupHelper.getMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                        setForceIconsMethod.invoke(menuPopupHelper, true)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w("GlobalSearchAdapter", "Failed to show pop-up menu icons.", e)
            }
        }

        fun removeItem(modelToRemove: Model) {
            val updatedData = data.filter { it != modelToRemove }
            setNewData(query.orEmpty(), updatedData)
        }

        private fun showContactActionsPopupMenu(model: Model.Contact) {
            // Inflate our menu
            val wrapper = ContextThemeWrapper(context, R.style.ContactPopupMenuStyle)
            val popupMenu = PopupMenu(wrapper, binding.searchResultTextParent)
            popupMenu.menuInflater.inflate(R.menu.menu_contact_actions, popupMenu.menu)

            // Force display of icons & tint them all red
            popupMenu.forceShowIcons()
            val dangerColour = ThemeUtil.getThemedColor(context, R.attr.danger)
            for (i in 0 until popupMenu.menu.size) {
                popupMenu.menu[i].icon?.setTint(dangerColour)
            }

            // Handle menu item clicks to block or delete the given contact
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_block -> {
                        binding.searchResultProfilePicture.recipient.let {
                            MessagingModuleConfiguration.shared.storage.setBlocked(listOf(it!!), isBlocked = true)
                        }
                        removeItem(model)
                        true
                    }
                    R.id.action_delete -> {
                        threadDB.deleteContact(model.contact.accountID)
                        removeItem(model)
                        true
                    }
                    else -> false
                }
            }

            // Finally show the popup
            popupMenu.show()
        }

        fun bind(query: String, model: Model) {
            binding.searchResultProfilePicture.recycle()
            when (model) {
                is Model.GroupConversation -> bindModel(query, model)
                is Model.Contact           -> bindModel(query, model)
                is Model.Message           -> bindModel(query, model)
                is Model.SavedMessages     -> bindModel(model)
                else -> throw InvalidParameterException("Can't display as ContentView")
            }
            binding.root.setOnClickListener { modelCallback(model) }

            // Display the block / delete popup on long-press of a contact which isn't us
            binding.root.setOnLongClickListener {
                if (model is Model.Contact && !model.isSelf) {
                    showContactActionsPopupMenu(model)
                }
                true
            }
        }
    }

    sealed class Model {
        data class Header(val title: GetString): Model() {
            constructor(@StringRes title: Int): this(GetString(title))
            constructor(title: String): this(GetString(title))
        }
        data class SubHeader(val title: GetString): Model() {
            constructor(@StringRes title: Int): this(GetString(title))
            constructor(title: String): this(GetString(title))
        }
        data class SavedMessages(val currentUserPublicKey: String): Model() // Note: "Note to Self" counts as SavedMessages rather than a Contact where `isSelf` is true.
        data class Contact(val contact: ContactModel, val name: String?, val isSelf: Boolean) : Model()
        data class GroupConversation(val groupRecord: GroupRecord) : Model()
        data class Message(val messageResult: MessageResult, val unread: Int, val isSelf: Boolean) : Model()
    }
}

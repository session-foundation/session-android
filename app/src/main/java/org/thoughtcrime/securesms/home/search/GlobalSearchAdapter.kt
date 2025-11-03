package org.thoughtcrime.securesms.home.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewGlobalSearchHeaderBinding
import network.loki.messenger.databinding.ViewGlobalSearchResultBinding
import network.loki.messenger.databinding.ViewGlobalSearchSubheaderBinding
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.search.model.MessageResult
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.util.DateUtils
import java.security.InvalidParameterException


class GlobalSearchAdapter(
    private val dateUtils: DateUtils,
    private val onContactClicked: (Model) -> Unit,
    private val onContactLongPressed: (Model.Contact) -> Unit,
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
        this.query = query

        if (this.data.size > 500 || newData.size > 500) {
            // For big data sets, we won't use DiffUtil to calculate the difference as it could be slow
            this.data = newData
            notifyDataSetChanged()
        } else {
            val diffResult =
                DiffUtil.calculateDiff(GlobalSearchDiff(this.query, query, data, newData), false)
            data = newData
            diffResult.dispatchUpdatesTo(this)
        }
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
                dateUtils = dateUtils,
                onContactClicked = onContactClicked,
                onContactLongPressed = onContactLongPressed
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
    }

    class ContentView(
        view: View,
        private val dateUtils: DateUtils,
        private val onContactClicked: (Model) -> Unit,
        private val onContactLongPressed: (Model.Contact) -> Unit,
    ) : RecyclerView.ViewHolder(view) {

        val binding = ViewGlobalSearchResultBinding.bind(view)

        fun bindPayload(newQuery: String, model: Model) {
            bindQuery(newQuery, model)
        }

        fun bind(query: String, model: Model) {
            when (model) {
                is Model.GroupConversation -> bindModel(query, model)
                is Model.Contact -> bindModel(query, model)
                is Model.Message -> bindModel(query, model, dateUtils)
                is Model.SavedMessages -> bindModel(model)

                else -> throw InvalidParameterException("Can't display as ContentView")
            }
            binding.root.setOnClickListener { onContactClicked(model) }

            // Display the block / delete popup on long-press of a contact which isn't us
            if (model is Model.Contact && !model.isSelf) {
                binding.root.setOnLongClickListener {
                    onContactLongPressed(model)
                    true
                }
            }
        }
    }

    sealed interface Model {
        data class Header(val title: GetString): Model {
            constructor(@StringRes title: Int): this(GetString(title))
            constructor(title: String): this(GetString(title))
        }
        data class SubHeader(val title: GetString): Model {
            constructor(@StringRes title: Int): this(GetString(title))
            constructor(title: String): this(GetString(title))
        }

        data class SavedMessages(val currentUserPublicKey: String): Model // Note: "Note to Self" counts as SavedMessages rather than a Contact where `isSelf` is true.
        data class Contact(val contact: Address.Conversable, val name: String, val isSelf: Boolean, val showProBadge: Boolean) : Model {
            constructor(contact: Recipient, isSelf: Boolean, showProBadge: Boolean):
                    this(contact.address as Address.Conversable, contact.displayName(false), isSelf, showProBadge)
        }
        data class GroupConversation(
            val isLegacy: Boolean,
            val groupId: String,
            val title: String,
            val legacyMembersString: String?,
            val showProBadge: Boolean
        ) : Model {
            constructor(groupRecord: GroupRecord, showProBadge: Boolean):
                    this(
                        isLegacy = groupRecord.isLegacyGroup,
                        groupId = groupRecord.encodedId,
                        title = groupRecord.title,
                        legacyMembersString = if (groupRecord.isLegacyGroup) {
                            val recipients = groupRecord.members.map {
                                MessagingModuleConfiguration.shared.recipientRepository.getRecipientSync(it)
                            }
                            recipients.joinToString(transform = { it.searchName })
                        } else {
                            null
                        },
                        showProBadge = showProBadge
                    )
        }
        data class Message(val messageResult: MessageResult, val unread: Int, val isSelf: Boolean, val showProBadge: Boolean) : Model
    }
}

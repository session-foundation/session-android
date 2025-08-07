package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.home.search.getSearchName
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils

@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = SelectContactsViewModel.Factory::class)
open class SelectContactsViewModel @AssistedInject constructor(
    private val configFactory: ConfigFactory,
    private val avatarUtils: AvatarUtils,
    private val proStatusManager: ProStatusManager,
    @ApplicationContext private val appContext: Context,
    @Assisted private val excludingAccountIDs: Set<Address>,
    @Assisted private val contactFiltering: (Recipient) -> Boolean, //  default will filter out blocked and unapproved contacts
) : ViewModel() {
    // Input: The search query
    private val mutableSearchQuery = MutableStateFlow("")

    // Input: The selected contact account IDs
    private val mutableSelectedContactAccountIDs = MutableStateFlow(emptySet<Address>())

    // Input: The manually added items to select from. This will be combined (and deduped) with the contacts
    // the user has. This is useful for selecting contacts that are not in the user's contacts list.
    private val mutableManuallyAddedContacts = MutableStateFlow(emptySet<Address>())

    // Output: The search query
    val searchQuery: StateFlow<String> get() = mutableSearchQuery

    // Output: the contact items to display and select from
    val contacts: StateFlow<List<ContactItem>> = combine(
        observeContacts(),
        mutableSearchQuery.debounce(100L),
        mutableSelectedContactAccountIDs,
        ::filterContacts
    ).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Output
    val currentSelected: Set<Address>
        get() = mutableSelectedContactAccountIDs.value

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeContacts() = (configFactory.configUpdateNotifications as Flow<Any>)
        .debounce(100L)
        .onStart { emit(Unit) }
        .flatMapLatest {
            mutableManuallyAddedContacts.map { manuallyAdded ->
                withContext(Dispatchers.Default) {
                    val allContacts =
                        (configFactory.withUserConfigs { configs -> configs.contacts.all() }
                            .asSequence()
                            .map { Address.fromSerialized(it.id) } + manuallyAdded)

                    val recipientContacts = if (excludingAccountIDs.isEmpty()) {
                        allContacts.toSet()
                    } else {
                        allContacts.filterNotTo(mutableSetOf()) { it in excludingAccountIDs }
                    }.map {
                        Recipient.from(
                            appContext,
                            it,
                            false
                        )
                    }

                    recipientContacts.filter(contactFiltering)
                }
            }
        }


    private suspend fun filterContacts(
        contacts: Collection<Recipient>,
        query: String,
        selectedAccountIDs: Set<Address>
    ): List<ContactItem> {
        val items = mutableListOf<ContactItem>()
        for (contact in contacts) {
            if (query.isBlank() || contact.getSearchName().contains(query, ignoreCase = true)) {
                val avatarData = avatarUtils.getUIDataFromRecipient(contact)
                items.add(
                    ContactItem(
                        name = contact.getSearchName(),
                        address = contact.address,
                        avatarUIData = avatarData,
                        selected = selectedAccountIDs.contains(contact.address),
                        showProBadge = proStatusManager.shouldShowProBadge(contact.address)
                    )
                )
            }
        }
        return items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun setManuallyAddedContacts(accountIDs: Set<Address>) {
        mutableManuallyAddedContacts.value = accountIDs
    }

    fun onSearchQueryChanged(query: String) {
        mutableSearchQuery.value = query
    }

    open fun onContactItemClicked(address: Address) {
        val newSet = mutableSelectedContactAccountIDs.value.toHashSet()
        if (!newSet.remove(address)) {
            newSet.add(address)
        }
        mutableSelectedContactAccountIDs.value = newSet
    }

    fun selectAccountIDs(accountIDs: Set<Address>) {
        mutableSelectedContactAccountIDs.value += accountIDs
    }

    fun clearSelection(){
        mutableSelectedContactAccountIDs.value = emptySet()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            excludingAccountIDs: Set<Address> = emptySet(),
            contactFiltering: (Recipient) -> Boolean = defaultFiltering,
        ): SelectContactsViewModel

        companion object {
            val defaultFiltering: (Recipient) -> Boolean = { !it.isBlocked && it.isApproved }
        }
    }
}

data class ContactItem(
    val address: Address,
    val name: String,
    val avatarUIData: AvatarUIData,
    val selected: Boolean,
    val showProBadge: Boolean
)

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.home.search.searchName
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils

@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = SelectContactsViewModel.Factory::class)
open class SelectContactsViewModel @AssistedInject constructor(
    private val configFactory: ConfigFactory,
    private val avatarUtils: AvatarUtils,
    private val proStatusManager: ProStatusManager,
    @Assisted private val excludingAccountIDs: Set<Address>,
    @Assisted private val contactFiltering: (Recipient) -> Boolean, //  default will filter out blocked and unapproved contacts
    private val recipientRepository: RecipientRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    // Input: The search query
    private val mutableSearchQuery = MutableStateFlow("")

    // Input: The selected contact account IDs
    private val mutableSelectedContacts = MutableStateFlow(emptySet<SelectedContact>())

    // Input: The manually added items to select from. This will be combined (and deduped) with the contacts
    // the user has. This is useful for selecting contacts that are not in the user's contacts list.
    private val mutableManuallyAddedContacts = MutableStateFlow(emptySet<Address>())

    // Output: The search query
    val searchQuery: StateFlow<String> get() = mutableSearchQuery

    private val contactsFlow = observeContacts()

    // Output: the contact items to display and select from
    val contacts: StateFlow<List<ContactItem>> = combine(
        contactsFlow,
        mutableSearchQuery.debounce(100L),
        mutableSelectedContacts,
        ::filterContacts
    ).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val hasContacts: StateFlow<Boolean> = contactsFlow
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Output: to be used by VMs extending this base VM
    val selectedContacts: StateFlow<Set<SelectedContact>> = mutableSelectedContacts

    // Output : snapshot helper
    val currentSelected: Set<Address>
        get() = mutableSelectedContacts.value.map { it.address }.toSet()

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
                        recipientRepository.getRecipient(it)
                    }

                    recipientContacts.filter(contactFiltering)
                }
            }
        }


    private fun filterContacts(
        contacts: Collection<Recipient>,
        query: String,
        selectedContacts: Set<SelectedContact>
    ): List<ContactItem> {
        val items = mutableListOf<ContactItem>()
        val selectedAddresses = selectedContacts.asSequence().map { it.address }.toSet()
        for (contact in contacts) {
            if (query.isBlank() || contact.searchName.contains(query, ignoreCase = true)) {
                val avatarData = avatarUtils.getUIDataFromRecipient(contact)
                items.add(
                    ContactItem(
                        name = contact.searchName,
                        address = contact.address,
                        avatarUIData = avatarData,
                        selected = selectedAddresses.contains(contact.address),
                        showProBadge = contact.shouldShowProBadge
                    )
                )
            }
        }
        return items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun setManuallyAddedContacts(accountIDs: Set<Address>) {
        mutableManuallyAddedContacts.value = accountIDs
    }

    // Used when getting results from a QR or AccountId input field
    fun setManuallySelectedAddress(address : Address){
        val selectedItem = SelectedContact(address, "")
        mutableSelectedContacts.value = setOf(selectedItem)
    }

    fun onSearchQueryChanged(query: String) {
        mutableSearchQuery.value = query
    }

    open fun onContactItemClicked(address: Address) {
        val newSet = mutableSelectedContacts.value.toHashSet()
        val selectedContact = contacts.value.find { it.address == address }

        if(selectedContact == null) return

        val item = SelectedContact(address = selectedContact.address, name = selectedContact.name)
        if (!newSet.remove(item)) {
            newSet.add(item)
        }
        mutableSelectedContacts.value = newSet
    }

    fun selectAccountIDs(accountIDs: Set<Address>) {
        val toAdd = accountIDs.map { address -> SelectedContact(address) }.toSet()
        mutableSelectedContacts.update { (it + toAdd).toSet() }
    }

    fun clearSelection(){
        mutableSelectedContacts.value = emptySet()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            excludingAccountIDs: Set<Address> = emptySet(),
            contactFiltering: (Recipient) -> Boolean = defaultFiltering,
        ): SelectContactsViewModel

        companion object {
            val defaultFiltering: (Recipient) -> Boolean = { !it.blocked && it.approved }
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

data class SelectedContact(
    val address: Address,
    val name: String = ""
)

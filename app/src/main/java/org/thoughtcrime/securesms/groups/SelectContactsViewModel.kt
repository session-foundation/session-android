package org.thoughtcrime.securesms.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.home.search.getSearchName
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils

@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = SelectContactsViewModel.Factory::class)
open class SelectContactsViewModel @AssistedInject constructor(
    private val configFactory: ConfigFactory,
    private val avatarUtils: AvatarUtils,
    @Assisted private val excludingAccountIDs: Set<AccountId>,
    @Assisted private val applyDefaultFiltering: Boolean, // true by default - If true will filter out blocked and unapproved contacts
    @Assisted private val scope: CoroutineScope,
    private val recipientRepository: RecipientRepository,
) : ViewModel() {
    // Input: The search query
    private val mutableSearchQuery = MutableStateFlow("")

    // Input: The selected contact account IDs
    private val mutableSelectedContactAccountIDs = MutableStateFlow(emptySet<AccountId>())

    // Input: The manually added items to select from. This will be combined (and deduped) with the contacts
    // the user has. This is useful for selecting contacts that are not in the user's contacts list.
    private val mutableManuallyAddedContacts = MutableStateFlow(emptySet<AccountId>())

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
    val currentSelected: Set<AccountId>
        get() = mutableSelectedContactAccountIDs.value

    override fun onCleared() {
        super.onCleared()

        scope.cancel()
    }

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
                            .map { AccountId(it.id) } + manuallyAdded)

                    val recipientContacts = if (excludingAccountIDs.isEmpty()) {
                        allContacts.toSet()
                    } else {
                        allContacts.filterNotTo(mutableSetOf()) { it in excludingAccountIDs }
                    }.map {
                        val address = Address.fromSerialized(it.hexString)
                        recipientRepository.getRecipient(address) ?: Recipient.empty(address)
                    }

                    if(applyDefaultFiltering){
                        recipientContacts.filter { !it.blocked && it.approved } // filter out blocked contacts and unapproved contacts
                    } else recipientContacts
                }
            }
        }


    private suspend fun filterContacts(
        contacts: Collection<Recipient>,
        query: String,
        selectedAccountIDs: Set<AccountId>
    ): List<ContactItem> {
        val items = mutableListOf<ContactItem>()
        for (contact in contacts) {
            if (query.isBlank() || contact.getSearchName().contains(query, ignoreCase = true)) {
                val accountId = AccountId(contact.address.toString())
                val avatarData = avatarUtils.getUIDataFromRecipient(contact)
                items.add(
                    ContactItem(
                        name = contact.getSearchName(),
                        accountID = accountId,
                        avatarUIData = avatarData,
                        selected = selectedAccountIDs.contains(accountId)
                    )
                )
            }
        }
        return items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun setManuallyAddedContacts(accountIDs: Set<AccountId>) {
        mutableManuallyAddedContacts.value = accountIDs
    }

    fun onSearchQueryChanged(query: String) {
        mutableSearchQuery.value = query
    }

    fun onContactItemClicked(accountID: AccountId) {
        val newSet = mutableSelectedContactAccountIDs.value.toHashSet()
        if (!newSet.remove(accountID)) {
            newSet.add(accountID)
        }
        mutableSelectedContactAccountIDs.value = newSet
    }

    fun selectAccountIDs(accountIDs: Set<AccountId>) {
        mutableSelectedContactAccountIDs.value += accountIDs
    }

    fun clearSelection(){
        mutableSelectedContactAccountIDs.value = emptySet()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            excludingAccountIDs: Set<AccountId> = emptySet(),
            applyDefaultFiltering: Boolean = true,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ): SelectContactsViewModel
    }
}

data class ContactItem(
    val accountID: AccountId,
    val name: String,
    val avatarUIData: AvatarUIData,
    val selected: Boolean,
)

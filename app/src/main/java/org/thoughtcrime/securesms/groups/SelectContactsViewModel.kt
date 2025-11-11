package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.COUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.OTHER_NAME_KEY
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.shouldShowProBadge
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.home.search.searchName
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.GetString
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
    // Input: The selected contact account IDs
    private val mutableSelectedContacts = MutableStateFlow(emptySet<SelectedContact>())

    // Input: The manually added items to select from. This will be combined (and deduped) with the contacts
    // the user has. This is useful for selecting contacts that are not in the user's contacts list.
    private val mutableManuallyAddedContacts = MutableStateFlow(emptySet<Address>())

    // Input: The search query
    private val mutableSearchQuery = MutableStateFlow("")
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

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // Output
    val currentSelected: Set<Address>
        get() = mutableSelectedContacts.value.map { it.address }.toSet()

    private val footerCollapsed = MutableStateFlow(false)
    private val showInviteContactsDialog = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(mutableSelectedContacts, footerCollapsed) { selected, isCollapsed ->
                buildFooterState(selected, isCollapsed)
            }.collect { footer ->
                _uiState.update { it.copy(footer = footer) }
            }
        }

        viewModelScope.launch {
            combine(showInviteContactsDialog, mutableSelectedContacts) { showDialog, selected ->
                buildInviteContactsDialogState(showDialog, selected)
            }.collect { state ->
                _uiState.update { it.copy(inviteContactsDialog = state) }
            }
        }
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
                        showProBadge = contact.proStatus.shouldShowProBadge()
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

    fun toggleFooter() {
        footerCollapsed.update { !it }
    }

    fun onSearchFocusChanged(isFocused :Boolean){
        _uiState.update { it.copy(isSearchFocused = isFocused) }
    }

    fun onDismissResend() {
        _uiState.update { it.copy(ongoingAction = null) }
    }

    fun removeSearchState(clearSelection : Boolean){
        onSearchFocusChanged(false)
        onSearchQueryChanged("")

        if(clearSelection){
            clearSelection()
        }
    }

    private fun buildFooterState(
        selected: Set<SelectedContact>,
        isCollapsed: Boolean
    ) : CollapsibleFooterState {
        val count = selected.size
        val visible = count > 0
        val title = if (count == 0) GetString("")
        else GetString(
            context.resources.getQuantityString(R.plurals.contactSelected, count, count)
        )

        return CollapsibleFooterState(
            visible = visible,
            collapsed = if (!visible) false else isCollapsed,
            footerActionTitle = title
        )
    }

    private fun buildInviteContactsDialogState(
        visible: Boolean,
        selected : Set<SelectedContact>
    ): InviteContactsDialogState {
        val count = selected.size
        val firstMember = selected.firstOrNull()

        val body: CharSequence = when (count) {
            1 -> Phrase.from(context, R.string.membersInviteShareDescription)
                .put(NAME_KEY, firstMember?.name)
                .format()

            2 -> {
                val secondMember = selected.elementAtOrNull(1)?.name
                Phrase.from(context, R.string.membersInviteShareDescriptionTwo)
                    .put(NAME_KEY, firstMember?.name)
                    .put(OTHER_NAME_KEY, secondMember)
                    .format()
            }

            0 -> ""
            else -> Phrase.from(context, R.string.membersInviteShareDescriptionMultiple)
                .put(NAME_KEY, firstMember?.name)
                .put(COUNT_KEY, count - 1)
                .format()
        }

        return InviteContactsDialogState(
            visible = visible,
            inviteContactsBody = body,
        )
    }

    fun toggleInviteContactsDialog(visible : Boolean){
        showInviteContactsDialog.value = visible
    }

    fun sendCommand(command: Commands) {
        when (command) {
            is Commands.ClearSelection -> clearSelection()
            is Commands.ToggleFooter -> toggleFooter()
            is Commands.CloseFooter -> clearSelection()
            is Commands.DismissResend -> onDismissResend()
            is Commands.ShowSendInvite -> toggleInviteContactsDialog(true)
            is Commands.DismissSendInvite -> toggleInviteContactsDialog(false)
            is Commands.ContactItemClick -> onContactItemClicked(command.address)
            is Commands.RemoveSearchState -> removeSearchState(command.clearSelection)
            is Commands.SearchFocusChange -> onSearchFocusChanged(command.focus)
            is Commands.SearchQueryChange -> onSearchQueryChanged(command.query)
        }
    }

    data class UiState(
        val isSearchFocused: Boolean = false,
        val ongoingAction: String? = null,

        val inviteContactsDialog: InviteContactsDialogState = InviteContactsDialogState(),
        val footer: CollapsibleFooterState = CollapsibleFooterState()
    )

    data class InviteContactsDialogState(
        val visible : Boolean = false,
        val inviteContactsBody : CharSequence = "",
    )

    data class CollapsibleFooterState(
        val visible: Boolean = false,
        val collapsed: Boolean = false,
        val footerActionTitle : GetString = GetString("")
    )

    sealed interface Commands {
        data object ClearSelection : Commands

        data object ToggleFooter : Commands

        data object CloseFooter : Commands

        data object DismissResend : Commands

        data object ShowSendInvite : Commands

        data object DismissSendInvite : Commands

        data class ContactItemClick(val address: Address) : Commands

        data class RemoveSearchState(val clearSelection: Boolean) : Commands

        data class SearchQueryChange(val query: String) : Commands

        data class SearchFocusChange(val focus: Boolean) : Commands
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

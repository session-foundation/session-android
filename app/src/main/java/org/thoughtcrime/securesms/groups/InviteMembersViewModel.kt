package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.COUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.OTHER_NAME_KEY
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.util.AvatarUtils

@HiltViewModel(assistedFactory = InviteMembersViewModel.Factory::class)
class InviteMembersViewModel @AssistedInject constructor(
    @Assisted private val groupAddress: Address.Group?,
    @Assisted private val excludingAccountIDs: Set<Address>,
    @param:ApplicationContext private val context: Context,
    configFactory: ConfigFactory,
    avatarUtils: AvatarUtils,
    proStatusManager: ProStatusManager,
    recipientRepository: RecipientRepository,
) : SelectContactsViewModel(
    configFactory = configFactory,
    excludingAccountIDs = excludingAccountIDs,
    contactFiltering = SelectContactsViewModel.Factory.defaultFiltering,
    avatarUtils = avatarUtils,
    proStatusManager = proStatusManager,
    recipientRepository = recipientRepository,
    context = context
) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val footerCollapsed = MutableStateFlow(false)
    private val showInviteContactsDialog = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(selectedContacts, footerCollapsed) { selected, isCollapsed ->
                buildFooterState(selected, isCollapsed)
            }.collect { footer ->
                _uiState.update { it.copy(footer = footer) }
            }
        }

        viewModelScope.launch {
            combine(selectedContacts, showInviteContactsDialog) { selected, showDialog ->
                buildInviteContactsDialogState(showDialog, selected)
            }.collect { state ->
                _uiState.update { it.copy(inviteContactsDialog = state) }
            }
        }
    }

    private fun buildFooterState(
        selected: Set<SelectedContact>,
        isCollapsed: Boolean
    ): CollapsibleFooterState {
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
        selected: Set<SelectedContact>,
    ): InviteContactsDialogState {
        val count = selected.size
        val sortedMembers = selected.sortedBy { it.address }
        val firstMember = sortedMembers.firstOrNull()

        val body: CharSequence = when (count) {
            1 -> {
                if (firstMember != null && firstMember.name.isNotEmpty()) {
                    Phrase.from(context, R.string.membersInviteShareDescription)
                        .put(NAME_KEY, firstMember?.name)
                        .format()
                } else {
                    context.getString(R.string.shareGroupMessageHistory)
                }
            }
            2 -> {
                val secondMember = sortedMembers.elementAtOrNull(1)?.name
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

        val inviteText =
            context.resources.getQuantityString(R.plurals.membersInviteSend, count, count)

        return InviteContactsDialogState(
            visible = visible,
            inviteContactsBody = body,
            inviteText = inviteText
        )
    }

    fun toggleFooter() {
        footerCollapsed.update { !it }
    }

    fun onSearchFocusChanged(isFocused: Boolean) {
        _uiState.update { it.copy(isSearchFocused = isFocused) }
    }

    fun toggleInviteContactsDialog(visible: Boolean) {
        showInviteContactsDialog.value = visible
    }

    fun removeSearchState(clearSelection: Boolean) {
        onSearchFocusChanged(false)
        onSearchQueryChanged("")

        if (clearSelection) {
            clearSelection()
        }
    }

    fun sendCommand(command: Commands) {
        when (command) {
            is Commands.ToggleFooter -> toggleFooter()

            is Commands.CloseFooter,
            Commands.ClearSelection -> clearSelection()

            is Commands.ContactItemClick -> onContactItemClicked(command.address)

            is Commands.HandleAccountId -> {
                setManuallySelectedAddress(command.address)
                toggleInviteContactsDialog(true)
            }

            is Commands.DismissSendInviteDialog -> toggleInviteContactsDialog(false)

            is Commands.ShowSendInviteDialog -> toggleInviteContactsDialog(true)

            is Commands.SearchFocusChange -> onSearchFocusChanged(command.focus)

            is Commands.SearchQueryChange -> onSearchQueryChanged(command.query)

            is Commands.RemoveSearchState -> removeSearchState(command.clearSelection)
        }
    }

    sealed interface Commands {
        data object ToggleFooter : Commands

        data object CloseFooter : Commands

        data object ShowSendInviteDialog : Commands

        data object DismissSendInviteDialog : Commands

        data object ClearSelection : Commands

        data class HandleAccountId(val address : Address) : Commands

        data class ContactItemClick(val address: Address) : Commands

        data class SearchFocusChange(val focus: Boolean) : Commands

        data class SearchQueryChange(val query: String) : Commands

        data class RemoveSearchState(val clearSelection: Boolean) : Commands
    }


    data class UiState(
        val isSearchFocused: Boolean = false,
        val ongoingAction: String? = null,

        val inviteContactsDialog: InviteContactsDialogState = InviteContactsDialogState(),
        val footer: CollapsibleFooterState = CollapsibleFooterState()
    )

    data class InviteContactsDialogState(
        val visible: Boolean = false,
        val inviteContactsBody: CharSequence = "",
        val inviteText: String = "",
    )

    data class CollapsibleFooterState(
        val visible: Boolean = false,
        val collapsed: Boolean = false,
        val footerActionTitle: GetString = GetString("")
    )

    @AssistedFactory
    interface Factory {
        fun create(
            groupAddress: Address.Group? = null,
            excludingAccountIDs: Set<Address> = emptySet(),
        ): InviteMembersViewModel
    }
}
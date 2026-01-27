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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.StringSubstitutionConstants.COUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.OTHER_NAME_KEY
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.util.AvatarUtils

@HiltViewModel(assistedFactory = PromoteMembersViewModel.Factory::class)
class PromoteMembersViewModel @AssistedInject constructor(
    @Assisted private val groupAddress: Address.Group,
    @ApplicationContext private val context: Context,
    storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol,
    private val recipientRepository: RecipientRepository,
    avatarUtils: AvatarUtils,
) : BaseGroupMembersViewModel(
    groupAddress = groupAddress,
    context = context,
    storage = storage,
    configFactory = configFactory,
    avatarUtils = avatarUtils,
    recipientRepository = recipientRepository
) {
    private val groupId = groupAddress.accountId

    private val _mutableSelectedMembers = MutableStateFlow(emptySet<GroupMemberState>())
    val selectedMembers: StateFlow<Set<GroupMemberState>> = _mutableSelectedMembers

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val _footerCollapsed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(
                selectedMembers,
                _footerCollapsed,
                ::buildFooterState
            ).collect { footer ->
                _uiState.update { it.copy(footer = footer) }
            }
        }

        viewModelScope.launch {
            selectedMembers
                .map { selected -> buildPromoteDialogBody(selected) }
                .collect { body ->
                    _uiState.update { it.copy(promoteDialogBody = body) }
                }
        }
    }

    fun onMemberItemClicked(member: GroupMemberState) {
        val newSet = _mutableSelectedMembers.value.toHashSet()
        if (!newSet.remove(member)) {
            newSet.add(member)
        }
        _mutableSelectedMembers.value = newSet
    }

    fun onSearchFocusChanged(isFocused: Boolean) {
        _uiState.update { it.copy(isSearchFocused = isFocused) }
    }

    fun toggleFooter() {
        _footerCollapsed.update { !it }
    }

    fun removeSearchState(clearSelection: Boolean) {
        onSearchFocusChanged(false)
        onSearchQueryChanged("")

        if (clearSelection) {
            clearSelection()
        }
    }

    fun clearSelection() {
        _mutableSelectedMembers.value = emptySet()
    }

    private fun buildFooterState(
        selected: Set<GroupMemberState>,
        isCollapsed: Boolean
    ): CollapsibleFooterState {
        val count = selected.size
        val visible = count > 0

        val title =
            if (count == 0) GetString("")
            else {
                GetString(
                    context.resources.getQuantityString(
                        R.plurals.memberSelected,
                        count,
                        count
                    )
                )
            }

        val footerAction = GetString(
            context.resources.getQuantityString(
                R.plurals.promoteMember,
                count, count
            )
        )

        return CollapsibleFooterState(
            visible = visible,
            collapsed = if (!visible) false else isCollapsed,
            footerTitle = title,
            footerActionLabel = footerAction
        )
    }

    private fun buildPromoteDialogBody(
        selected: Set<GroupMemberState>
    ): String {
        val count = selected.size
        val firstMember = selected.firstOrNull()

        val body: CharSequence = when (count) {
            1 -> {
                Phrase.from(context, R.string.adminPromoteDescription)
                    .put(NAME_KEY, firstMember?.name)
                    .format()
            }

            2 -> {
                val secondMember = selected.elementAtOrNull(1)?.name
                Phrase.from(context, R.string.adminPromoteTwoDescription)
                    .put(NAME_KEY, firstMember?.name)
                    .put(OTHER_NAME_KEY, secondMember)
                    .format()
            }

            0 -> ""
            else -> Phrase.from(context, R.string.adminPromoteMoreDescription)
                .put(NAME_KEY, firstMember?.name)
                .put(COUNT_KEY, count - 1)
                .format()
        }

        return body.toString()
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ShowPromoteDialog -> {
                _uiState.update { it.copy(showPromoteDialog = true) }
            }

            is Commands.DismissPromoteDialog -> {
                _uiState.update { it.copy(showPromoteDialog = false) }
            }

            is Commands.ShowConfirmDialog -> {
                _uiState.update { it.copy(showConfirmDialog = true) }
            }

            is Commands.DismissConfirmDialog -> {
                _uiState.update { it.copy(showConfirmDialog = false) }
            }

            is Commands.ToggleFooter -> toggleFooter()

            is Commands.CloseFooter,
            is Commands.ClearSelection -> clearSelection()

            is Commands.MemberClick -> onMemberItemClicked(command.member)

            is Commands.RemoveSearchState -> removeSearchState(command.clearSelection)

            is Commands.SearchFocusChange -> onSearchFocusChanged(command.focus)

            is Commands.SearchQueryChange -> onSearchQueryChanged(command.query)
        }
    }

    sealed interface Commands {
        data object ShowPromoteDialog : Commands
        data object DismissPromoteDialog : Commands

        data object ShowConfirmDialog : Commands
        data object DismissConfirmDialog : Commands

        data object ToggleFooter : Commands
        data object CloseFooter : Commands
        data object ClearSelection : Commands

        data class RemoveSearchState(val clearSelection: Boolean) : Commands
        data class SearchQueryChange(val query: String) : Commands
        data class SearchFocusChange(val focus: Boolean) : Commands

        data class MemberClick(val member: GroupMemberState) : Commands
    }

    data class UiState(
        // search UI state:
        val searchQuery: String = "",
        val isSearchFocused: Boolean = false,

        val showConfirmDialog: Boolean = false,

        val showPromoteDialog: Boolean = false,
        val promoteDialogBody: String = "",

        //Collapsible footer
        val footer: CollapsibleFooterState = CollapsibleFooterState()
    )

    data class CollapsibleFooterState(
        val visible: Boolean = false,
        val collapsed: Boolean = false,
        val footerTitle: GetString = GetString(""),
        val footerActionLabel: GetString = GetString("")
    )


    @AssistedFactory
    interface Factory {
        fun create(
            groupAddress: Address.Group,
        ): PromoteMembersViewModel
    }
}
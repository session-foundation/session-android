package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.getOrNull
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupInviteException
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.StringSubstitutionConstants.COUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.OTHER_NAME_KEY
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.util.AvatarUtils


@HiltViewModel(assistedFactory = ManageGroupMembersViewModel.Factory::class)
class ManageGroupMembersViewModel @AssistedInject constructor(
    @Assisted private val groupAddress: Address.Group,
    @Assisted private val navigator: UINavigator<ConversationSettingsDestination>,
    @param:ApplicationContext private val context: Context,
    storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol,
    private val groupManager: GroupManagerV2,
    private val recipientRepository: RecipientRepository,
    avatarUtils: AvatarUtils,
) : BaseGroupMembersViewModel(groupAddress, context, storage, configFactory, avatarUtils, recipientRepository) {
    private val groupId = groupAddress.accountId

    // Output: The name of the group. This is the current name of the group, not the name being edited.
    val groupName: StateFlow<String> = groupInfo
        .map { it?.first?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    // Output: whether we should show the "add members" button
    val showAddMembers: StateFlow<Boolean> = groupInfo
        .map { it?.first?.isUserAdmin == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    // Output:
    val excludingAccountIDsFromContactSelection: Set<String>
        get() = groupInfo.value?.second?.mapTo(hashSetOf()) { it.accountId.hexString }.orEmpty()

    private val _mutableSelectedMembers = MutableStateFlow(emptySet<GroupMemberState>())
    val selectedMembers: StateFlow<Set<GroupMemberState>> = _mutableSelectedMembers

    private val footerCollapsed = MutableStateFlow(false)

   private val optionsList: List<OptionsItem> by lazy {
        listOf(
            OptionsItem(
                name = context.getString(R.string.membersInvite),
                icon = R.drawable.ic_user_round_plus,
                onClick = ::navigateToInviteContacts
            ),
            OptionsItem(
                name = context.getString(R.string.accountIdOrOnsInvite),
                icon = R.drawable.ic_user_round_search,
                onClick = ::navigateToInviteAccountId
            )
        )
    }

    private val _uiState = MutableStateFlow(UiState(options = optionsList))
    val uiState: StateFlow<UiState> = _uiState

    private val showRemoveMembersDialog = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(showRemoveMembersDialog, selectedMembers, groupName) { showRemove, selected, group ->
                buildRemoveMembersDialogState(showRemove, selected, group)
            }.collect { state ->
                _uiState.update { it.copy(removeMembersDialog = state) }
            }
        }

        viewModelScope.launch {
            combine(selectedMembers, footerCollapsed) { selected, isCollapsed ->
                buildFooterState(selected, isCollapsed)
            }.collect { footer ->
                _uiState.update { it.copy(footer = footer) }
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
    fun onSearchFocusChanged(isFocused :Boolean){
        _uiState.update { it.copy(isSearchFocused = isFocused) }
    }

    private fun navigateToInviteContacts() {
        viewModelScope.launch {
            navigator.navigate(
                ConversationSettingsDestination.RouteInviteToGroup(
                    groupAddress,
                    excludingAccountIDsFromContactSelection.toList()
                )
            )
        }
    }

    private fun navigateToInviteAccountId(){
        viewModelScope.launch {
            navigator.navigate(
                ConversationSettingsDestination.RouteInviteAccountIdToGroup(
                    groupAddress,
                    excludingAccountIDsFromContactSelection.toList()
                )
            )
        }
    }

    fun onSendInviteClicked(contacts: Set<Address>) {
        _uiState.update {
            it.copy(
                ongoingAction = context.resources.getQuantityString(
                    R.plurals.groupInviteSending,
                    contacts.size,
                    contacts.size
                )
            )
        }

        performGroupOperation(
            showLoading = false,
            errorMessage = { err ->
                if (err is GroupInviteException) {
                    err.format(context, recipientRepository).toString()
                } else {
                    null
                }
            }
        ) {
            groupManager.inviteMembers(
                groupId,
                contacts.map { AccountId(it.toString()) }.toList(),
                shareHistory = false,
                isReinvite = false,
            )
        }
    }

    fun onResendInviteClicked() {
        if (selectedMembers.value.isEmpty()) return
        performGroupOperation(
            showLoading = false,
            errorMessage = { err ->
                if (err is GroupInviteException) {
                    err.format(context, recipientRepository).toString()
                } else {
                    null
                }
            }
        ) {
            // Look up current member configs once
            val invites: List<MemberInvite> = configFactory.withGroupConfigs(groupId) { cfg ->
                selectedMembers.value.map { member ->
                    val shareHistory =
                        cfg.groupMembers.getOrNull(member.accountId.hexString)?.supplement == true
                    MemberInvite(id = member.accountId, shareHistory = shareHistory)
                }
            }

            removeSearchState(true)

            _uiState.update { it ->
                it.copy(error = context.resources.getQuantityString(
                    R.plurals.resendingInvite,
                    invites.size,
                    invites.size
                ))
            }

            // Reinvite with per-member shareHistory
            groupManager.reinviteMembers(
                group = groupId,
                invites = invites
            )
        }
    }

    fun removeSearchState(clearSelection : Boolean){
        onSearchFocusChanged(false)
        onSearchQueryChanged("")

        if(clearSelection){
            clearSelection()
        }
    }

    fun onPromoteContact(memberSessionId: AccountId) {
        performGroupOperation(showLoading = false) {
            groupManager.promoteMember(groupId, listOf(memberSessionId), isRepromote = false)
        }
    }

    fun onRemoveContact(removeMessages: Boolean) {
        _uiState.update { it ->
            it.copy(ongoingAction =context.resources.getQuantityString(
                R.plurals.removingMember,
                selectedMembers.value.size,
                selectedMembers.value.size
            ))
        }

        performGroupOperation(showLoading = false) {
            val accountIdList = selectedMembers.value.map { it.accountId }

            removeSearchState(true)

            groupManager.removeMembers(
                groupAccountId = groupId,
                removedMembers = accountIdList,
                removeMessages = removeMessages
            )
        }
    }

    fun onResendPromotionClicked(memberSessionId: AccountId) {
        performGroupOperation(showLoading = false) {
            groupManager.promoteMember(groupId, listOf(memberSessionId), isRepromote = true)
        }
    }

    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Perform a group operation, such as inviting a member, removing a member.
     *
     * This is a helper function that encapsulates the common error handling and progress tracking.
     */
    private fun performGroupOperation(
        showLoading: Boolean = true,
        errorMessage: ((Throwable) -> String?)? = null,
        operation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(inProgress = true) }
            }

            // We need to use GlobalScope here because we don't want
            // any group operation to be cancelled when the view model is cleared.
            @Suppress("OPT_IN_USAGE")
            val task = GlobalScope.async {
                operation()
            }

            try {
                task.await()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = errorMessage?.invoke(e)
                            ?: context.getString(R.string.errorUnknown)
                    )
                }
            } finally {
                if (showLoading) {
                    _uiState.update { it.copy(inProgress = false) }
                }
            }
        }
    }

    fun clearSelection(){
        _mutableSelectedMembers.value = emptySet()
    }

    fun toggleFooter() {
        footerCollapsed.update { !it }
    }

    fun onDismissResend() {
        _uiState.update { it.copy(ongoingAction = null) }
    }

    private fun toggleRemoveDialog(visible : Boolean){
        showRemoveMembersDialog.value = visible
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ShowRemoveDialog -> toggleRemoveDialog(true)

            is Commands.DismissRemoveDialog -> toggleRemoveDialog(false)

            is Commands.RemoveMembers -> onRemoveContact(command.removeMessages)

            is Commands.ClearSelection,

            is Commands.CloseFooter -> clearSelection()

            is Commands.ToggleFooter -> toggleFooter()

            is Commands.DismissError -> onDismissError()

            is Commands.DismissResend -> onDismissResend()

            is Commands.MemberClick -> onMemberItemClicked(command.member)

            is Commands.RemoveSearchState -> removeSearchState(command.clearSelection)

            is Commands.SearchFocusChange -> onSearchFocusChanged(command.focus)

            is Commands.SearchQueryChange -> onSearchQueryChanged(command.query)
        }
    }

    private fun buildRemoveMembersDialogState(
        visible: Boolean,
        selected: Set<GroupMemberState>,
        group: String
    ): RemoveMembersDialogState {
        val count = selected.size
        val firstMember = selected.firstOrNull()

        val body: CharSequence = when (count) {
            1 -> Phrase.from(context, R.string.groupRemoveDescription)
                .put(NAME_KEY, firstMember?.name)
                .put(GROUP_NAME_KEY, group)
                .format()

            2 -> {
                val secondMember = selected.elementAtOrNull(1)?.name
                Phrase.from(context, R.string.groupRemoveDescriptionTwo)
                    .put(NAME_KEY, firstMember?.name)
                    .put(OTHER_NAME_KEY, secondMember)
                    .put(GROUP_NAME_KEY, group)
                    .format()
            }

            0 -> ""
            else -> Phrase.from(context, R.string.groupRemoveDescriptionMultiple)
                .put(NAME_KEY, firstMember?.name)
                .put(COUNT_KEY, count - 1)
                .put(GROUP_NAME_KEY, group)
                .format()
        }

        val removeMemberOnly =
            context.resources.getQuantityString(R.plurals.removeMember, count, count)
        val removeMessages =
            context.resources.getQuantityString(R.plurals.removeMemberMessages, count, count)

        return RemoveMembersDialogState(
            visible = visible,
            removeMemberBody = body,
            removeMemberText = removeMemberOnly,
            removeMessagesText = removeMessages
        )
    }

    private fun buildFooterState(
        selected: Set<GroupMemberState>,
        isCollapsed: Boolean
    ): CollapsibleFooterState {
        val count = selected.size
        val visible = count > 0
        val title = if (count == 0) GetString("") else GetString(
            context.resources.getQuantityString(R.plurals.memberSelected, count, count)
        )

        val trayItems = listOf(
            CollapsibleFooterItemData(
                label = GetString(
                    context.resources.getQuantityString(R.plurals.resendInvite, count, count)
                ),
                buttonLabel = GetString(context.getString(R.string.resend)),
                isDanger = false,
                onClick = { onResendInviteClicked() }
            ),
            CollapsibleFooterItemData(
                label = GetString(
                    context.resources.getQuantityString(R.plurals.removeMember, count, count)
                ),
                buttonLabel = GetString(context.getString(R.string.remove)),
                isDanger = true,
                onClick = { onCommand(Commands.ShowRemoveDialog) }
            )
        )

        return CollapsibleFooterState(
            visible = visible,
            collapsed = if (!visible) false else isCollapsed,
            footerActionTitle = title,
            footerActionItems = trayItems
        )
    }

    data class UiState(
        val options : List<OptionsItem> = emptyList(),

        val inProgress: Boolean = false,
        val error: String? = null,
        val ongoingAction: String? = null,

        // search UI state:
        val searchQuery: String = "",
        val isSearchFocused: Boolean = false,

        // Remove member dialog
        val removeMembersDialog: RemoveMembersDialogState = RemoveMembersDialogState(),

        //Collapsible footer
        val footer: CollapsibleFooterState = CollapsibleFooterState()
    )

    data class CollapsibleFooterState(
        val visible: Boolean = false,
        val collapsed: Boolean = false,
        val footerActionTitle : GetString = GetString(""),
        val footerActionItems : List<CollapsibleFooterItemData> = emptyList()
    )

    data class RemoveMembersDialogState(
        val visible : Boolean = false,
        val removeMemberBody : CharSequence = "",
        val removeMemberText : String = "",
        val removeMessagesText : String = ""
    )

    data class OptionsItem(
        val name: String,
        @DrawableRes val icon: Int,
        @StringRes val qaTag: Int? = null,
        val onClick: () -> Unit
    )

    sealed interface Commands {
        data object ShowRemoveDialog : Commands
        data object DismissRemoveDialog : Commands

        data object DismissError : Commands

        data object DismissResend : Commands

        data object ToggleFooter : Commands

        data object CloseFooter : Commands

        data object ClearSelection : Commands

        data class RemoveSearchState(val clearSelection : Boolean) : Commands

        data class SearchQueryChange(val query : String) : Commands

        data class SearchFocusChange(val focus : Boolean) : Commands
        data class RemoveMembers(val removeMessages: Boolean) : Commands

        data class MemberClick(val member: GroupMemberState) : Commands
    }

    @AssistedFactory
    interface Factory {
        fun create(
            groupAddress: Address.Group,
            navigator: UINavigator<ConversationSettingsDestination>
        ): ManageGroupMembersViewModel
    }
}

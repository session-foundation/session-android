package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupInviteException
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.util.AvatarUtils

/**
 * Admin screen:
 *  - Shows admins + their promotion status
 *  - Lets you select admins with failed/sent promotions
 *  - Bottom tray: "Resend promotions"
 *
 * No removing members, no invites here.
 */
@HiltViewModel(assistedFactory = ManageGroupAdminsViewModel.Factory::class)
class ManageGroupAdminsViewModel @AssistedInject constructor(
    @Assisted private val groupAddress: Address.Group,
    @Assisted private val navigator: UINavigator<ConversationSettingsDestination>,
    @Assisted private val openPromoteMembers: Boolean,
    @ApplicationContext private val context: Context,
    storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol,
    private val groupManager: GroupManagerV2,
    private val recipientRepository: RecipientRepository,
    avatarUtils: AvatarUtils,
) : BaseGroupMembersViewModel(
    groupAddress = groupAddress,
    context = context,
    storage = storage,
    configFactory = configFactory,
    avatarUtils = avatarUtils,
    recipientRepository = recipientRepository,
    groupManager = groupManager
) {
    private val groupId = groupAddress.accountId

    /**
     * One option for admins for now: "Promote members"
     */
    private val optionsList: List<OptionsItem> by lazy {
        listOf(
            OptionsItem(
                // use plural version of this string resource
                name = context.resources.getQuantityString(R.plurals.promoteMember, 2, 2),
                icon = R.drawable.ic_add_admin_custom,
                onClick = ::navigateToPromoteMembers,
                qaTag = R.string.qa_manage_members_promote_members
            )
        )
    }

    private val _mutableSelectedAdmins = MutableStateFlow(emptySet<GroupMemberState>())
    val selectedAdmins: StateFlow<Set<GroupMemberState>> = _mutableSelectedAdmins

    private val footerCollapsed = MutableStateFlow(false)

    private val _uiState = MutableStateFlow(UiState(options = optionsList))
    val uiState: StateFlow<UiState> = _uiState

    init {
        // Build footer from selected admins + collapsed state
        viewModelScope.launch {
            combine(
                selectedAdmins,
                footerCollapsed,
                ::buildFooterState
            ).collect { footer ->
                _uiState.update { it.copy(footer = footer) }
            }
        }

        if (openPromoteMembers) {
            // Only runs once for this nav entry, so no loop on back
            navigateToPromoteMembers()
        }
    }

    fun onAdminItemClicked(member: GroupMemberState) {
        val newSet = _mutableSelectedAdmins.value.toHashSet()
        if (!newSet.remove(member)) {
            newSet.add(member)
        }
        _mutableSelectedAdmins.value = newSet
    }

    fun onSearchFocusChanged(isFocused: Boolean) {
        _uiState.update { it.copy(isSearchFocused = isFocused) }
    }

    private fun navigateToPromoteMembers() {
        viewModelScope.launch {
            navigator.navigate(
                destination = ConversationSettingsDestination.RoutePromoteMembers(groupAddress),
                debounce = false
            )
        }
    }

    private fun setLoading(isLoading : Boolean){
        _uiState.update { it.copy(inProgress = isLoading) }
    }

    /**
     * Send promotions to all selected admins (explicit selection from caller).
     */
    fun onSendPromotionsClicked(selectedAdmins: Set<GroupMemberState>) {
        sendPromotions(members = selectedAdmins, isRepromote = false)
    }

    /**
     * Resend promotions using locally selected admins.
     * Used in the parent screen with admin list
     */
    fun onResendPromotionsClicked() {
        sendPromotions(isRepromote = true)
    }

    private fun sendPromotions(
        members: Set<GroupMemberState> = selectedAdmins.value,
        isRepromote: Boolean
    ) {
        if (members.isEmpty()) return

        val accountIds = members.map { it.accountId }

        val sendingPromotionText = context.resources.getQuantityString(
            if (isRepromote) R.plurals.resendingPromotion else R.plurals.sendingPromotion,
            accountIds.size,
            accountIds.size
        )

        showToast(sendingPromotionText)

        performGroupOperationCore(
            showLoading = false,
            setLoading = ::setLoading,
            errorMessage = { err ->
                if (err is GroupInviteException) {
                    err.format(context, recipientRepository).toString()
                } else {
                    null
                }
            }
        ) {
            removeSearchState(clearSelection = true)

            groupManager.promoteMember(
                groupId,
                accountIds,
                isRepromote = isRepromote
            )
        }
    }

    fun removeSearchState(clearSelection: Boolean) {
        onSearchFocusChanged(false)
        onSearchQueryChanged("")

        if (clearSelection) {
            clearSelection()
        }
    }

    fun clearSelection() {
        _mutableSelectedAdmins.value = emptySet()
    }

    fun toggleFooter() {
        footerCollapsed.update { !it }
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
                        R.plurals.adminSelected,
                        count,
                        count
                    )
                )
            }

        val trayItems = listOf(
            CollapsibleFooterItemData(
                label = GetString(
                    context.resources.getQuantityString(R.plurals.resendPromotion, count, count)
                ),
                buttonLabel = GetString(context.getString(R.string.resend)),
                isDanger = false,
                onClick = { onResendPromotionsClicked() }
            )
        )

        return CollapsibleFooterState(
            visible = visible,
            collapsed = if (!visible) false else isCollapsed,
            footerActionTitle = title,
            footerActionItems = trayItems
        )
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ToggleFooter -> toggleFooter()
            is Commands.CloseFooter,
            is Commands.ClearSelection -> clearSelection()
            is Commands.SelfClick ->  showToast(context.getString(R.string.adminStatusYou))
            is Commands.MemberClick -> onAdminItemClicked(command.member)
            is Commands.RemoveSearchState -> removeSearchState(command.clearSelection)
            is Commands.SearchFocusChange -> onSearchFocusChanged(command.focus)
            is Commands.SearchQueryChange -> onSearchQueryChanged(command.query)
        }
    }

    data class UiState(
        val options: List<OptionsItem> = emptyList(),

        val inProgress: Boolean = false,

        // search UI state:
        val searchQuery: String = "",
        val isSearchFocused: Boolean = false,

        //Collapsible footer
        val footer: CollapsibleFooterState = CollapsibleFooterState(),
    )

    data class CollapsibleFooterState(
        val visible: Boolean = false,
        val collapsed: Boolean = false,
        val footerActionTitle: GetString = GetString(""),
        val footerActionItems: List<CollapsibleFooterItemData> = emptyList()
    )

    data class OptionsItem(
        val name: String,
        @DrawableRes val icon: Int,
        @StringRes val qaTag: Int? = null,
        val onClick: () -> Unit
    )

    sealed interface Commands {
        data object ToggleFooter : Commands
        data object CloseFooter : Commands
        data object ClearSelection : Commands

        data object SelfClick : Commands

        class RemoveSearchState(val clearSelection: Boolean) : Commands
        data class SearchQueryChange(val query: String) : Commands
        data class SearchFocusChange(val focus: Boolean) : Commands

        data class MemberClick(val member: GroupMemberState) : Commands
    }

    @AssistedFactory
    interface Factory {
        fun create(
            groupAddress: Address.Group,
            navigator: UINavigator<ConversationSettingsDestination>,
            navigateToPromoteMembers: Boolean
        ): ManageGroupAdminsViewModel
    }
}
package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.getOrNull
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupInviteException
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.v2.utilities.TextUtilities.textSizeInBytes


const val MAX_GROUP_NAME_BYTES = 100

@HiltViewModel(assistedFactory = EditGroupViewModel.Factory::class)
class EditGroupViewModel @AssistedInject constructor(
    @Assisted private val groupId: AccountId,
    @ApplicationContext private val context: Context,
    private val storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol,
    private val groupManager: GroupManagerV2,
) : BaseGroupMembersViewModel(groupId, context, storage, configFactory) {
    // Input/Output state
    private val mutableEditingName = MutableStateFlow<String?>(null)

    // Input/Output: the name that has been written and submitted for change to push to the server,
    // but not yet confirmed by the server. When this state is present, it takes precedence over
    // the group name in the group info.
    private val mutablePendingEditedName = MutableStateFlow<String?>(null)

    // Output: The name of the group being edited. Null if it's not in edit mode, not to be confused
    // with empty string, where it's a valid editing state.
    val editingName: StateFlow<String?> get() = mutableEditingName

    // Output: whether the group name can be edited. This is true if the group is loaded successfully.
    val canEditGroupName: StateFlow<Boolean> = groupInfo
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Output: The name of the group. This is the current name of the group, not the name being edited.
    val groupName: StateFlow<String> = combine(groupInfo
        .map { it?.first?.name.orEmpty() }, mutablePendingEditedName) { name, pendingName -> pendingName ?: name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    // Output: whether we should show the "add members" button
    val showAddMembers: StateFlow<Boolean> = groupInfo
        .map { it?.first?.isUserAdmin == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    // Output: Intermediate states
    private val mutableInProgress = MutableStateFlow(false)
    val inProgress: StateFlow<Boolean> get() = mutableInProgress

    // show action bottom sheet
    private val _clickedMember: MutableStateFlow<GroupMemberState?> = MutableStateFlow(null)
    val clickedMember: StateFlow<GroupMemberState?> get() = _clickedMember

    // Output: errors
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = mutableError

    // Output:
    val excludingAccountIDsFromContactSelection: Set<AccountId>
        get() = groupInfo.value?.second?.mapTo(hashSetOf()) { it.accountId }.orEmpty()

    fun onContactSelected(contacts: Set<AccountId>) {
        performGroupOperation(
            showLoading = false,
            errorMessage = { err ->
                if (err is GroupInviteException) {
                    err.format(context, storage).toString()
                } else {
                    null
                }
            }
        ) {
            groupManager.inviteMembers(
                groupId,
                contacts.toList(),
                shareHistory = false,
                isReinvite = false,
            )
        }
    }

    fun onResendInviteClicked(contactSessionId: AccountId) {
        performGroupOperation(
            showLoading = false,
            errorMessage = { err ->
                if (err is GroupInviteException) {
                    err.format(context, storage).toString()
                } else {
                    null
                }
            }
        ) {
            val historyShared = configFactory.withGroupConfigs(groupId) {
                it.groupMembers.getOrNull(contactSessionId.hexString)
            }?.supplement == true

            groupManager.inviteMembers(
                groupId,
                listOf(contactSessionId),
                shareHistory = historyShared,
                isReinvite = true,
            )
        }
    }

    fun onPromoteContact(memberSessionId: AccountId) {
        performGroupOperation(showLoading = false) {
            groupManager.promoteMember(groupId, listOf(memberSessionId), isRepromote = false)
        }
    }

    fun onRemoveContact(contactSessionId: AccountId, removeMessages: Boolean) {
        performGroupOperation(showLoading = false) {
            groupManager.removeMembers(
                groupAccountId = groupId,
                removedMembers = listOf(contactSessionId),
                removeMessages = removeMessages
            )
        }
    }

    fun onResendPromotionClicked(memberSessionId: AccountId) {
        performGroupOperation(showLoading = false) {
            groupManager.promoteMember(groupId, listOf(memberSessionId), isRepromote = true)
        }
    }

    fun onEditNameClicked() {
        mutableEditingName.value = groupInfo.value?.first?.name.orEmpty()
    }

    fun onCancelEditingNameClicked() {
        mutableEditingName.value = null
    }

    fun onEditingNameChanged(value: String) {
        mutableEditingName.value = value
    }

    fun onEditNameConfirmClicked() {
        val newName = mutableEditingName.value

        if (newName.isNullOrBlank()) {
            mutableError.value = context.getString(R.string.groupNameEnterPlease)
            return
        }

        // validate name length (needs to be less than 100 bytes)
        if(newName.textSizeInBytes() > MAX_GROUP_NAME_BYTES){
            mutableError.value = context.getString(R.string.groupNameEnterShorter)
            return
        }

        // Move the edited name into the pending state
        mutableEditingName.value = null
        mutablePendingEditedName.value = newName

        performGroupOperation {
            try {
                groupManager.setName(groupId, newName)
            } finally {
                // As soon as the operation is done, clear the pending state,
                // no matter if it's successful or not. So that we update the UI to reflect the
                // real state.
                mutablePendingEditedName.value = null
            }
        }
    }

    fun onDismissError() {
        mutableError.value = null
    }

    /**
     * Perform a group operation, such as inviting a member, removing a member.
     *
     * This is a helper function that encapsulates the common error handling and progress tracking.
     */
    private fun performGroupOperation(
        showLoading: Boolean = true,
        errorMessage: ((Throwable) -> String?)? = null,
        operation: suspend () -> Unit) {
        viewModelScope.launch {
            if (showLoading) {
                mutableInProgress.value = true
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
                mutableError.value = errorMessage?.invoke(e)
                    ?: context.getString(R.string.errorUnknown)
            } finally {
                if (showLoading) {
                    mutableInProgress.value = false
                }
            }
        }
    }

    fun onMemberClicked(groupMember: GroupMemberState){
        // if the member is clickable (ie, not 'you') but is an admin with no possible actions,
        // show a toast mentioning they can't be removed
        if(!groupMember.canEdit && groupMember.showAsAdmin){
            mutableError.value = context.getString(R.string.adminCannotBeRemoved)
        } else { // otherwise pass in the clicked member to display the action sheet
            _clickedMember.value = groupMember
        }
    }

    fun hideActionBottomSheet(){
        _clickedMember.value = null
    }

    @AssistedFactory
    interface Factory {
        fun create(groupId: AccountId): EditGroupViewModel
    }
}

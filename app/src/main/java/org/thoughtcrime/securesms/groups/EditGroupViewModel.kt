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
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.util.AvatarUtils



@HiltViewModel(assistedFactory = EditGroupViewModel.Factory::class)
class EditGroupViewModel @AssistedInject constructor(
    @Assisted private val groupId: AccountId,
    @ApplicationContext private val context: Context,
    storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol,
    private val groupManager: GroupManagerV2,
    avatarUtils: AvatarUtils,
    private val recipientRepository: RecipientRepository,
) : BaseGroupMembersViewModel(groupId, context, storage, configFactory, avatarUtils, recipientRepository) {

    // Output: The name of the group. This is the current name of the group, not the name being edited.
    val groupName: StateFlow<String> = groupInfo
        .map { it?.first?.name.orEmpty() }
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
    val excludingAccountIDsFromContactSelection: Set<String>
        get() = groupInfo.value?.second?.mapTo(hashSetOf()) { it.accountId.hexString }.orEmpty()

    fun onContactSelected(contacts: Set<AccountId>) {
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
                    err.format(context, recipientRepository).toString()
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

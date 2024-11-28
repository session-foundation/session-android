package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.AssistedFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.GroupDisplayInfo
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.getMemberName
import org.session.libsignal.utilities.AccountId
import java.util.EnumSet


abstract class BaseGroupMembersViewModel (
    private val groupId: AccountId,
    @ApplicationContext private val context: Context,
    private val storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol
) : ViewModel() {
    // Output: the source-of-truth group information. Other states are derived from this.
    protected val groupInfo: StateFlow<Pair<GroupDisplayInfo, List<GroupMemberState>>?> =
        configFactory.configUpdateNotifications
            .filter {
                it is ConfigUpdateNotification.GroupConfigsUpdated && it.groupId == groupId ||
                        it is ConfigUpdateNotification.UserConfigsMerged
            }
            .onStart { emit(ConfigUpdateNotification.GroupConfigsUpdated(groupId)) }
            .map { _ ->
                withContext(Dispatchers.Default) {
                    val currentUserId = AccountId(checkNotNull(storage.getUserPublicKey()) {
                        "User public key is null"
                    })

                    val displayInfo = storage.getClosedGroupDisplayInfo(groupId.hexString)
                        ?: return@withContext null

                    val memberState = storage.getMembers(groupId.hexString)
                        .map { member ->
                            createGroupMember(
                                member = member,
                                myAccountId = currentUserId,
                                amIAdmin = displayInfo.isUserAdmin,
                            )
                        }

                    displayInfo to sortMembers(memberState, currentUserId)
                }
          }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Output: the list of the members and their state in the group.
    val members: StateFlow<List<GroupMemberState>> = groupInfo
        .map { it?.second.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private fun createGroupMember(
        member: GroupMember,
        myAccountId: AccountId,
        amIAdmin: Boolean,
    ): GroupMemberState {
        val name = member.getMemberName(configFactory)
        val isMyself = member.accountId == myAccountId

        val highlightStatus = member.status in EnumSet.of(
            GroupMember.Status.INVITE_FAILED,
            GroupMember.Status.PROMOTION_FAILED
        )

        return GroupMemberState(
            accountId = member.accountId,
            name = name,
            canRemove = amIAdmin && member.accountId != myAccountId
                    && !member.isAdminOrBeingPromoted && !member.removed,
            canPromote = amIAdmin && member.accountId != myAccountId
                    && !member.isAdminOrBeingPromoted && !member.removed,
            canResendPromotion = amIAdmin && member.accountId != myAccountId
                    && member.status == GroupMember.Status.PROMOTION_FAILED && !member.removed,
            canResendInvite = amIAdmin && member.accountId != myAccountId
                    && !member.removed
                    && (member.status == GroupMember.Status.INVITE_SENT || member.status == GroupMember.Status.INVITE_FAILED),
            status = member.status?.takeIf { !isMyself }, // Status is only meant for other members
            highlightStatus = highlightStatus,
            showAsAdmin = member.isAdminOrBeingPromoted,
            clickable = !isMyself
        )
    }

    private fun sortMembers(members: List<GroupMemberState>, currentUserId: AccountId) =
        members.sortedWith(
            compareBy<GroupMemberState>{
                when (it.status) {
                    GroupMember.Status.INVITE_FAILED -> 0 // Failed invite comes first
                    GroupMember.Status.INVITE_NOT_SENT -> 1 // then "Sending invite"
                    GroupMember.Status.INVITE_SENT -> 2 // then "Invite sent"
                    GroupMember.Status.PROMOTION_NOT_SENT -> 3 // then "Sending promotion"
                    GroupMember.Status.PROMOTION_SENT -> 4 // then "Promotion sent"
                    else -> 5
                }
            }
                .thenBy { !it.showAsAdmin } // Admins come first
                .thenBy { it.accountId != currentUserId } // Being myself comes first
                .thenBy { it.name } // Sort by name
                .thenBy { it.accountId } // Last resort: sort by account ID
        )

    @AssistedFactory
    interface Factory {
        fun create(groupId: AccountId): EditGroupViewModel
    }
}

data class GroupMemberState(
    val accountId: AccountId,
    val name: String,
    val status: GroupMember.Status?,
    val highlightStatus: Boolean,
    val showAsAdmin: Boolean,
    val canResendInvite: Boolean,
    val canResendPromotion: Boolean,
    val canRemove: Boolean,
    val canPromote: Boolean,
    val clickable: Boolean
) {
    val canEdit: Boolean get() = canRemove || canPromote || canResendInvite || canResendPromotion
}

// Function to get the label dynamically using the context
fun GroupMember.Status.getLabel(context: Context): String {
    return when (this) {
        GroupMember.Status.INVITE_FAILED -> context.getString(R.string.groupInviteFailed)
        GroupMember.Status.INVITE_NOT_SENT -> context.resources.getQuantityString(R.plurals.groupInviteSending, 1)
        GroupMember.Status.INVITE_SENT -> context.getString(R.string.groupInviteSent)
        GroupMember.Status.PROMOTION_FAILED -> context.getString(R.string.adminPromotionFailed)
        GroupMember.Status.PROMOTION_NOT_SENT -> context.resources.getQuantityString(R.plurals.adminSendingPromotion, 1)
        GroupMember.Status.PROMOTION_SENT -> context.getString(R.string.adminPromotionSent)
        GroupMember.Status.REMOVED,
        GroupMember.Status.REMOVED_UNKNOWN,
        GroupMember.Status.REMOVED_INCLUDING_MESSAGES -> context.getString(R.string.groupPendingRemoval)

        GroupMember.Status.INVITE_UNKNOWN,
        GroupMember.Status.INVITE_ACCEPTED,
        GroupMember.Status.PROMOTION_UNKNOWN,
        GroupMember.Status.PROMOTION_ACCEPTED -> ""
    }
}
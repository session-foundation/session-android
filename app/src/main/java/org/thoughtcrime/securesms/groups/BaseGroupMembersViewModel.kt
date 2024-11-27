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


abstract class BaseGroupMembersViewModel (
    private val groupId: AccountId,
    @ApplicationContext private val context: Context,
    private val storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol
) : ViewModel() {

    // Input: invite/promote member's intermediate states. This is needed because we don't have
    // a state that we can map into in the config system. The config system only provides "sent", "failed", etc.
    // The intermediate states are needed to show the user that the operation is in progress, and the
    // states are limited to the view model (i.e. lost if the user navigates away). This is a trade-off
    // between the complexity of the config system and the user experience.
    protected val memberPendingState = MutableStateFlow<Map<AccountId, MemberPendingState>>(emptyMap())

    // Output: the source-of-truth group information. Other states are derived from this.
    protected val groupInfo: StateFlow<Pair<GroupDisplayInfo, List<GroupMemberState>>?> =
        combine(
            configFactory.configUpdateNotifications
                .filter {
                    it is ConfigUpdateNotification.GroupConfigsUpdated && it.groupId == groupId ||
                            it is ConfigUpdateNotification.UserConfigsMerged
                }
                .onStart { emit(ConfigUpdateNotification.GroupConfigsUpdated(groupId)) },
            memberPendingState
        ) { _, pending ->
            withContext(Dispatchers.Default) {
                val currentUserId = AccountId(checkNotNull(storage.getUserPublicKey()) {
                    "User public key is null"
                })

                val displayInfo = storage.getClosedGroupDisplayInfo(groupId.hexString)
                    ?: return@withContext null

                val members = storage.getMembers(groupId.hexString)
                    .filterTo(mutableListOf()) { !it.removed }

                val memberState = members.map { member ->
                    createGroupMember(
                        member = member,
                        myAccountId = currentUserId,
                        amIAdmin = displayInfo.isUserAdmin,
                        pendingState = pending[member.accountId]
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
        pendingState: MemberPendingState?
    ): GroupMemberState {
        var status = GroupMemberStatus.MEMBER
        var highlightStatus = false
        var name = member.getMemberName(configFactory)
        var isMyself = false

        when {
            member.accountIdString() == myAccountId.hexString -> {
                name = context.getString(R.string.you)
                isMyself = true
            }

            member.removed -> {
                status = GroupMemberStatus.REMOVED
            }

            pendingState == MemberPendingState.Inviting -> {
                status = GroupMemberStatus.INVITE_SENDING
            }

            pendingState == MemberPendingState.Promoting -> {
                status = GroupMemberStatus.PROMOTION_SENDING
            }

            member.status == GroupMember.Status.PROMOTION_SENT -> {
                status = GroupMemberStatus.PROMOTION_SENT
            }

            member.status == GroupMember.Status.INVITE_SENT -> {
                status = GroupMemberStatus.INVITE_SENT
            }

            member.status == GroupMember.Status.INVITE_FAILED -> {
                status = GroupMemberStatus.INVITE_FAILED
                highlightStatus = true
            }

            member.status == GroupMember.Status.REMOVED ||
                    member.status == GroupMember.Status.REMOVED_INCLUDING_MESSAGES -> {
                status = GroupMemberStatus.REMOVAL_PENDING
            }

            member.status == GroupMember.Status.PROMOTION_FAILED -> {
                status = GroupMemberStatus.PROMOTION_FAILED
                highlightStatus = true
            }
        }

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
            status = status,
            highlightStatus = highlightStatus,
            showAsAdmin = member.isAdminOrBeingPromoted,
            clickable = !isMyself
        )
    }

    private fun sortMembers(members: List<GroupMemberState>, currentUserId: AccountId) =
        members.sortedWith(
            compareBy<GroupMemberState>{
                when (it.status) {
                    GroupMemberStatus.INVITE_FAILED -> 0 // Failed invite comes first
                    GroupMemberStatus.INVITE_SENDING -> 1 // then "Sending invite"
                    GroupMemberStatus.INVITE_SENT -> 2 // then "Invite sent"
                    GroupMemberStatus.PROMOTION_SENDING -> 3 // then "Sending promotion"
                    GroupMemberStatus.PROMOTION_SENT -> 4 // then "Promotion sent"
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

    protected enum class MemberPendingState {
        Inviting,
        Promoting,
    }
}

data class GroupMemberState(
    val accountId: AccountId,
    val name: String,
    val status: GroupMemberStatus,
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


enum class GroupMemberStatus{
    INVITE_FAILED,
    INVITE_SENDING,
    INVITE_SENT,
    PROMOTION_FAILED,
    PROMOTION_SENDING,
    PROMOTION_SENT,
    REMOVAL_PENDING,
    REMOVED,
    MEMBER;

    // Function to get the label dynamically using the context
    fun getLabel(context: Context): String {
        return when (this) {
            INVITE_FAILED -> context.getString(R.string.groupInviteFailed)
            INVITE_SENDING -> context.resources.getQuantityString(R.plurals.groupInviteSending, 1)
            INVITE_SENT -> context.getString(R.string.groupInviteSent)
            PROMOTION_FAILED -> context.getString(R.string.adminPromotionFailed)
            PROMOTION_SENDING -> context.resources.getQuantityString(R.plurals.adminSendingPromotion, 1)
            PROMOTION_SENT -> context.getString(R.string.adminPromotionSent)
            REMOVAL_PENDING -> context.getString(R.string.groupPendingRemoval)
            REMOVED, MEMBER -> ""
        }
    }
}
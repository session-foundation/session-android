package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.AssistedFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.allWithStatus
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.GroupDisplayInfo
import org.session.libsession.utilities.UsernameUtils
import org.session.libsignal.utilities.AccountId

abstract class BaseGroupMembersViewModel (
    private val groupId: AccountId,
    @ApplicationContext private val context: Context,
    private val storage: StorageProtocol,
    private val usernameUtils: UsernameUtils,
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

                    val memberState = configFactory.withGroupConfigs(groupId) { it.groupMembers.allWithStatus() }
                        .map { (member, status) ->
                            createGroupMember(
                                member = member,
                                status = status,
                                myAccountId = currentUserId,
                                amIAdmin = displayInfo.isUserAdmin,
                            )
                        }
                        .toList()

                    displayInfo to sortMembers(memberState, currentUserId)
                }
          }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Output: the list of the members and their state in the group.
    val members: StateFlow<List<GroupMemberState>> = groupInfo
        .map { it?.second.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private fun createGroupMember(
        member: GroupMember,
        status: GroupMember.Status,
        myAccountId: AccountId,
        amIAdmin: Boolean,
    ): GroupMemberState {
        val memberAccountId = AccountId(member.accountId())
        val isMyself = memberAccountId == myAccountId
        val name = if (isMyself) {
            context.getString(R.string.you)
        } else {
            usernameUtils.getContactNameWithAccountID(memberAccountId.hexString, groupId)
        }

        val highlightStatus = status in EnumSet.of(
            GroupMember.Status.INVITE_FAILED,
            GroupMember.Status.PROMOTION_FAILED
        )

        return GroupMemberState(
            accountId = memberAccountId,
            name = name,
            canRemove = amIAdmin && memberAccountId != myAccountId
                    && !member.isAdminOrBeingPromoted(status) && !member.isRemoved(status),
            canPromote = amIAdmin && memberAccountId != myAccountId
                    && !member.isAdminOrBeingPromoted(status) && !member.isRemoved(status),
            canResendPromotion = amIAdmin && memberAccountId != myAccountId
                    && status == GroupMember.Status.PROMOTION_FAILED && !member.isRemoved(status),
            canResendInvite = amIAdmin && memberAccountId != myAccountId
                    && !member.isRemoved(status)
                    && (status == GroupMember.Status.INVITE_SENT || status == GroupMember.Status.INVITE_FAILED),
            status = status.takeIf { !isMyself }, // Status is only meant for other members
            highlightStatus = highlightStatus,
            showAsAdmin = member.isAdminOrBeingPromoted(status),
            clickable = !isMyself,
            statusLabel = getMemberLabel(status, context, amIAdmin),
        )
    }

    private fun getMemberLabel(status: GroupMember.Status, context: Context, amIAdmin: Boolean): String {
        return when (status) {
            GroupMember.Status.INVITE_FAILED -> context.getString(R.string.groupInviteFailed)
            GroupMember.Status.INVITE_SENDING -> context.resources.getQuantityString(R.plurals.groupInviteSending, 1)
            GroupMember.Status.INVITE_SENT -> context.getString(R.string.groupInviteSent)
            GroupMember.Status.PROMOTION_FAILED -> context.getString(R.string.adminPromotionFailed)
            GroupMember.Status.PROMOTION_SENDING -> context.resources.getQuantityString(R.plurals.adminSendingPromotion, 1)
            GroupMember.Status.PROMOTION_SENT -> context.getString(R.string.adminPromotionSent)
            GroupMember.Status.REMOVED,
            GroupMember.Status.REMOVED_UNKNOWN,
            GroupMember.Status.REMOVED_INCLUDING_MESSAGES -> {
                if (amIAdmin) {
                    context.getString(R.string.groupPendingRemoval)
                } else {
                    ""
                }
            }

            GroupMember.Status.INVITE_NOT_SENT -> context.getString(R.string.groupInviteNotSent)
            GroupMember.Status.PROMOTION_NOT_SENT -> context.getString(R.string.adminPromotionNotSent)

            GroupMember.Status.INVITE_UNKNOWN,
            GroupMember.Status.INVITE_ACCEPTED,
            GroupMember.Status.PROMOTION_UNKNOWN,
            GroupMember.Status.PROMOTION_ACCEPTED -> ""
        }
    }

    // Refer to notion doc for the sorting logic
    private fun sortMembers(members: List<GroupMemberState>, currentUserId: AccountId) =
        members.sortedWith(
            compareBy<GroupMemberState>{
                when (it.status) {
                    GroupMember.Status.INVITE_FAILED -> 0
                    GroupMember.Status.INVITE_NOT_SENT -> 1
                    GroupMember.Status.INVITE_SENDING -> 2
                    GroupMember.Status.INVITE_SENT -> 3
                    GroupMember.Status.INVITE_UNKNOWN -> 4
                    GroupMember.Status.REMOVED,
                    GroupMember.Status.REMOVED_UNKNOWN,
                    GroupMember.Status.REMOVED_INCLUDING_MESSAGES -> 5
                    GroupMember.Status.PROMOTION_FAILED -> 6
                    GroupMember.Status.PROMOTION_NOT_SENT -> 7
                    GroupMember.Status.PROMOTION_SENDING -> 8
                    GroupMember.Status.PROMOTION_SENT -> 9
                    GroupMember.Status.PROMOTION_UNKNOWN -> 10
                    null,
                    GroupMember.Status.INVITE_ACCEPTED,
                    GroupMember.Status.PROMOTION_ACCEPTED -> 11
                }
            }
                .thenBy { !it.showAsAdmin } // Admins come first
                .thenBy { it.accountId != currentUserId } // Being myself comes first
                .thenComparing(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) // Sort by name (case insensitive)
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
    val clickable: Boolean,
    val statusLabel: String,
) {
    val canEdit: Boolean get() = canRemove || canPromote || canResendInvite || canResendPromotion
}

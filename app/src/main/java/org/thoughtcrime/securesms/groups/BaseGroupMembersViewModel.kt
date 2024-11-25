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
                .filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
                .filter { it.groupId == groupId }
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
                sortMembers(members, currentUserId)

                displayInfo to members.map { member ->
                    createGroupMember(
                        member = member,
                        myAccountId = currentUserId,
                        amIAdmin = displayInfo.isUserAdmin,
                        pendingState = pending[member.accountId]
                    )
                }
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
        var status = ""
        var highlightStatus = false
        var name = member.getMemberName(configFactory)
        var isMyself = false

        when {
            member.accountIdString() == myAccountId.hexString -> {
                name = context.getString(R.string.you)
                isMyself = true
            }

            member.removed -> {
                status = ""
            }

            pendingState == MemberPendingState.Inviting -> {
                status = context.resources.getQuantityString(R.plurals.groupInviteSending, 1)
            }

            pendingState == MemberPendingState.Promoting -> {
                status = context.resources.getQuantityString(R.plurals.adminSendingPromotion, 1)
            }

            member.status == GroupMember.Status.PROMOTION_SENT -> {
                status = context.getString(R.string.adminPromotionSent)
            }

            member.status == GroupMember.Status.INVITE_SENT -> {
                status = context.getString(R.string.groupInviteSent)
            }

            member.status == GroupMember.Status.INVITE_FAILED -> {
                status = context.getString(R.string.groupInviteFailed)
                highlightStatus = true
            }

            member.status == GroupMember.Status.PROMOTION_FAILED -> {
                status = context.getString(R.string.adminPromotionFailed)
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

    private fun sortMembers(members: MutableList<GroupMember>, currentUserId: AccountId) {
        members.sortWith(
            compareBy(
                { !it.inviteFailed }, // Failed invite comes first (as false value is less than true)
                { memberPendingState.value[it.accountId] != MemberPendingState.Inviting }, // "Sending invite" comes first
                { it.status != GroupMember.Status.INVITE_SENT }, // "Invite sent" comes first
                { !it.isAdminOrBeingPromoted }, // Admins come first
                { it.accountIdString() != currentUserId.hexString }, // Being myself comes first
                { it.name }, // Sort by name
                { it.accountIdString() } // Last resort: sort by account ID
            )
        )
    }

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
    val status: String,
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

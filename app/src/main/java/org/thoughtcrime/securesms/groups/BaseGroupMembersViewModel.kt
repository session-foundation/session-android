package org.thoughtcrime.securesms.groups

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.allWithStatus
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.GroupDisplayInfo
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import java.util.EnumSet

abstract class BaseGroupMembersViewModel(
    groupAddress: Address.Group,
    @param:ApplicationContext private val context: Context,
    private val storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol,
    private val avatarUtils: AvatarUtils,
    private val recipientRepository: RecipientRepository
) : ViewModel() {
    private val groupId = groupAddress.accountId

    // Output: the source-of-truth group information. Other states are derived from this.
    @OptIn(FlowPreview::class)
    protected val groupInfo: StateFlow<Pair<GroupDisplayInfo, List<GroupMemberState>>?> =
        (configFactory.configUpdateNotifications
            .filter {
                it is ConfigUpdateNotification.GroupConfigsUpdated && it.groupId == groupId ||
                        it is ConfigUpdateNotification.UserConfigsUpdated
            } as Flow<*>)
            .onStart { emit(Unit) }
            .map { _ ->
                withContext(Dispatchers.Default) {
                    val currentUserId = AccountId(checkNotNull(storage.getUserPublicKey()) {
                        "User public key is null"
                    })

                    val displayInfo = storage.getClosedGroupDisplayInfo(groupId.hexString)
                        ?: return@withContext null

                    val rawMembers =
                        configFactory.withGroupConfigs(groupId) { it.groupMembers.allWithStatus() }

                    val memberState = mutableListOf<GroupMemberState>()
                    for ((member, status) in rawMembers) {
                        memberState.add(
                            createGroupMember(
                                member = member, status = status,
                                shouldShowProBadge = recipientRepository.getRecipient(
                                    member.accountId().toAddress()
                                ).shouldShowProBadge,
                                myAccountId = currentUserId,
                                amIAdmin = displayInfo.isUserAdmin
                            )
                        )
                    }

                    displayInfo to sortMembers(memberState, currentUserId)
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Current group name (for header / text, if needed)
    val groupName: StateFlow<String> = groupInfo
        .map { it?.first?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    private val mutableSearchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> get() = mutableSearchQuery

    // Output: the list of the members and their state in the group.
    @OptIn(FlowPreview::class)
    val members: StateFlow<List<GroupMemberState>> = combine(
        groupInfo.map { it?.second.orEmpty() },
        mutableSearchQuery.debounce(100L),
        ::filterContacts
    ).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Output: List of only NON-ADMINS
    val nonAdminMembers: StateFlow<List<GroupMemberState>> = members
        .map { list -> list.filter { !it.showAsAdmin } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Output : List of active members that can be promoted
    val activeMembers: StateFlow<List<GroupMemberState>> = members
        .map { list -> list.filter { !it.showAsAdmin && it.status == GroupMember.Status.INVITE_ACCEPTED } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val hasActiveMembers: StateFlow<Boolean> =
        groupInfo
            .map { pair -> pair?.second.orEmpty().any { !it.showAsAdmin && it.status == GroupMember.Status.INVITE_ACCEPTED  } }
            .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val hasNonAdminMembers: StateFlow<Boolean> =
        groupInfo
            .map { pair -> pair?.second.orEmpty().any { !it.showAsAdmin } }
            .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // Output: List of only ADMINS
    val adminMembers: StateFlow<List<GroupMemberState>> = members
        .map { list ->
            list.filter { it.showAsAdmin }
                .sortedWith(
                    compareBy<GroupMemberState> { adminOrder(it) }
                        .thenComparing(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                        .thenBy { it.accountId }
                )
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun onSearchQueryChanged(query: String) {
        mutableSearchQuery.value = query
    }

    private fun filterContacts(
        contacts: List<GroupMemberState>,
        query: String,
    ): List<GroupMemberState> {
        return if(query.isBlank()) contacts
        else contacts.filter { it.name.contains(query, ignoreCase = true) }
    }

    private suspend fun createGroupMember(
        member: GroupMember,
        status: GroupMember.Status,
        shouldShowProBadge: Boolean,
        myAccountId: AccountId,
        amIAdmin: Boolean,
    ): GroupMemberState {
        val memberAccountId = AccountId(member.accountId())
        val isMyself = memberAccountId == myAccountId
        val name = if (isMyself) {
            context.getString(R.string.you)
        } else {
            recipientRepository.getRecipient(Address.fromSerialized(memberAccountId.hexString))
                .displayName()
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
            status = status.takeIf { !isMyself }, // status is only for other members
            highlightStatus = highlightStatus,
            showAsAdmin = member.isAdminOrBeingPromoted(status),
            showProBadge = shouldShowProBadge,
            avatarUIData = avatarUtils.getUIDataFromAccountId(memberAccountId.hexString),
            clickable = !isMyself,
            statusLabel = getMemberLabel(status, context, amIAdmin),
            isSelf = isMyself
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

    // Refer to manage members/admin PRD for the sorting logic
    private fun sortMembers(members: List<GroupMemberState>, currentUserId: AccountId) =
        members.sortedWith(
            compareBy<GroupMemberState> { stateOrder(it.status) }
                .thenBy { it.accountId != currentUserId }
                .thenComparing(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .thenBy { it.accountId }
        )

    fun showToast(text: String) {
        Toast.makeText(
            context, text, Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Perform a group operation, such as inviting a member, removing a member.
     *
     * This is a helper function that encapsulates the common error handling and progress tracking.
     */
    protected fun performGroupOperationCore(
        showLoading: Boolean = false,
        setLoading: (Boolean) -> Unit = {},
        errorMessage: ((Throwable) -> String?)? = null,
        operation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            if (showLoading) setLoading(true)

            // We need to use GlobalScope here because we don't want
            // any group operation to be cancelled when the view model is cleared.
            @Suppress("OPT_IN_USAGE")
            val task = GlobalScope.async {
                operation()
            }

            try {
                task.await()
            } catch (e: Throwable) {
                val msg = errorMessage?.invoke(e) ?: context.getString(R.string.errorUnknown)
                showToast(msg)
            } finally {
                if (showLoading) setLoading(false)
            }
        }
    }
}

private fun stateOrder(status: GroupMember.Status?): Int = when (status) {
    // 1. Invite failed
    GroupMember.Status.INVITE_FAILED -> 0
    // 2. Invite not sent
    GroupMember.Status.INVITE_NOT_SENT -> 1
    // 3. Sending invite
    GroupMember.Status.INVITE_SENDING -> 2
    // 4. Invite sent
    GroupMember.Status.INVITE_SENT -> 3
    // 5. Invite status unknown
    GroupMember.Status.INVITE_UNKNOWN -> 4
    // 6. Pending removal
    GroupMember.Status.REMOVED,
    GroupMember.Status.REMOVED_UNKNOWN,
    GroupMember.Status.REMOVED_INCLUDING_MESSAGES -> 5
    // 7. Member (everything else)
    else -> 6
}

private fun adminOrder(state: GroupMemberState): Int {
    if (state.isSelf) return 7 // "You" always last
    return when (state.status) {
        GroupMember.Status.PROMOTION_FAILED -> 1
        GroupMember.Status.PROMOTION_NOT_SENT -> 2
        GroupMember.Status.PROMOTION_UNKNOWN -> 3
        GroupMember.Status.PROMOTION_SENDING -> 4
        GroupMember.Status.PROMOTION_SENT -> 5
        else -> 6
    }
}

data class GroupMemberState(
    val accountId: AccountId,
    val avatarUIData: AvatarUIData,
    val name: String,
    val status: GroupMember.Status?,
    val highlightStatus: Boolean,
    val showAsAdmin: Boolean,
    val showProBadge: Boolean,
    val canResendInvite: Boolean,
    val canResendPromotion: Boolean,
    val canRemove: Boolean,
    val canPromote: Boolean,
    val clickable: Boolean,
    val statusLabel: String,
    val isSelf: Boolean
) {
    val canEdit: Boolean get() = canRemove || canPromote || canResendInvite || canResendPromotion
}

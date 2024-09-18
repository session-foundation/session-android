package org.session.libsession.messaging.groups

import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsignal.utilities.AccountId

/**
 * Business logic handling group v2 operations like inviting members,
 * removing members, promoting members, leaving groups, etc.
 */
interface GroupManagerV2 {
    suspend fun inviteMembers(
        group: AccountId,
        newMembers: List<AccountId>,
        shareHistory: Boolean
    )

    suspend fun removeMembers(
        groupAccountId: AccountId,
        removedMembers: List<AccountId>,
        removeMessages: Boolean
    )

    suspend fun handleMemberLeft(message: GroupUpdated, closedGroupId: AccountId)

    suspend fun leaveGroup(group: AccountId, deleteOnLeave: Boolean)

    suspend fun promoteMember(group: AccountId, members: List<AccountId>)
}
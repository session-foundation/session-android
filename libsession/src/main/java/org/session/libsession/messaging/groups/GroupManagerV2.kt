package org.session.libsession.messaging.groups

import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateDeleteMemberContentMessage
import org.session.libsignal.utilities.AccountId

/**
 * Business logic handling group v2 operations like inviting members,
 * removing members, promoting members, leaving groups, etc.
 */
interface GroupManagerV2 {
    suspend fun createGroup(
        groupName: String,
        groupDescription: String,
        members: Set<Contact>
    ): Recipient

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

    suspend fun handleInvitation(
        groupId: AccountId,
        groupName: String,
        authData: ByteArray,
        inviter: AccountId,
        inviteMessageHash: String?
    )

    suspend fun handlePromotion(
        groupId: AccountId,
        groupName: String,
        adminKey: ByteArray,
        promoter: AccountId,
        promoteMessageHash: String?
    )

    suspend fun respondToInvitation(groupId: AccountId, approved: Boolean): Unit?

    suspend fun handleInviteResponse(groupId: AccountId, sender: AccountId, approved: Boolean)

    suspend fun handleKicked(groupId: AccountId)

    suspend fun setName(groupId: AccountId, newName: String)

    suspend fun requestMessageDeletion(groupId: AccountId, messageHashes: List<String>)

    suspend fun handleDeleteMemberContent(
        groupId: AccountId,
        deleteMemberContent: GroupUpdateDeleteMemberContentMessage,
        sender: AccountId,
        senderIsVerifiedAdmin: Boolean,
    )
}
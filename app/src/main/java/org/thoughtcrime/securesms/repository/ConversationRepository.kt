package org.thoughtcrime.securesms.repository

import kotlinx.coroutines.flow.Flow
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ThreadRecord

interface ConversationRepository {
    fun observeConversationList(): Flow<List<ThreadRecord>>

    /**
     * Returns a list of threads that are visible to the user. Note that this
     * list includes both approved and unapproved threads.
     */

    fun getConversationList(): List<ThreadRecord>


    val conversationListAddressesFlow: Flow<Set<Address.Conversable>>

    fun saveDraft(threadId: Long, text: String)
    fun getDraft(threadId: Long): String?
    fun clearDrafts(threadId: Long)
    fun inviteContactsToCommunity(communityRecipient: Recipient, contacts: Collection<Address>)
    fun setBlocked(recipient: Address, blocked: Boolean)
    fun markAsDeletedLocally(messages: Set<MessageRecord>, displayedMessage: String)
    fun deleteMessages(messages: Set<MessageRecord>)
    fun deleteAllLocalMessagesInThreadFromSenderOfMessage(messageRecord: MessageRecord)
    fun isGroupReadOnly(recipient: Recipient): Boolean
    fun getLastSentMessageID(threadId: Long): Flow<MessageId?>

    suspend fun deleteCommunityMessagesRemotely(
        community: Address.Community,
        messages: Set<MessageRecord>
    )
    suspend fun delete1on1MessagesRemotely(
        recipient: Address,
        messages: Set<MessageRecord>
    )
    suspend fun deleteNoteToSelfMessagesRemotely(
        recipient: Address,
        messages: Set<MessageRecord>
    )
    suspend fun deleteLegacyGroupMessagesRemotely(
        recipient: Address,
        messages: Set<MessageRecord>
    )

    suspend fun deleteGroupV2MessagesRemotely(recipient: Address, messages: Set<MessageRecord>)

    suspend fun banUser(community: Address.Community, userId: AccountId): Result<Unit>
    suspend fun banAndDeleteAll(community: Address.Community, userId: AccountId): Result<Unit>
    suspend fun deleteMessageRequest(thread: ThreadRecord): Result<Unit>
    suspend fun clearAllMessageRequests(): Result<Unit>
    suspend fun acceptMessageRequest(recipient: Address.Conversable): Result<Unit>
    suspend fun declineMessageRequest(recipient: Address.Conversable): Result<Unit>
    fun hasReceived(threadId: Long): Boolean
    fun getInvitingAdmin(threadId: Long): Address?

    /**
     * This will delete all messages from the database.
     * If a groupId is passed along, and if the user is an admin of that group,
     * this will also remove the messages from the swarm and update
     * the delete_before flag for that group to now
     *
     * Returns the amount of deleted messages
     */
    suspend fun clearAllMessages(threadId: Long, groupId: AccountId?): Int
}

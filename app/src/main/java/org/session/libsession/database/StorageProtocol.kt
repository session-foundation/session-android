package org.session.libsession.database

import android.content.Context
import android.net.Uri
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.KeyPair
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.Job
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.visible.Attachment
import org.session.libsession.messaging.messages.visible.Profile
import org.session.libsession.messaging.messages.visible.Reaction
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupDisplayInfo
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import network.loki.messenger.libsession_util.util.GroupMember as LibSessionGroupMember

interface StorageProtocol {

    // General
    fun getUserPublicKey(): String?
    fun getUserED25519KeyPair(): KeyPair?
    fun getUserX25519KeyPair(): ECKeyPair
    fun getUserBlindedAccountId(serverPublicKey: String): AccountId?
    fun getUserProfile(): Profile

    // Signal
    fun getOrGenerateRegistrationID(): Int

    // Jobs
    fun persistJob(job: Job)
    fun markJobAsSucceeded(jobId: String)
    fun markJobAsFailedPermanently(jobId: String)
    fun getAllPendingJobs(vararg types: String): Map<String,Job?>
    fun getAttachmentUploadJob(attachmentID: Long): AttachmentUploadJob?
    fun getMessageSendJob(messageSendJobID: String): MessageSendJob?
    fun resumeMessageSendJobIfNeeded(messageSendJobID: String)
    fun isJobCanceled(job: Job): Boolean
    fun cancelPendingMessageSendJobs(threadID: Long)

    // Authorization
    fun getAuthToken(room: String, server: String): String?
    fun setAuthToken(room: String, server: String, newValue: String)
    fun removeAuthToken(room: String, server: String)

    // Servers
    fun setServerCapabilities(server: String, capabilities: List<String>)
    fun getServerCapabilities(server: String): List<String>?

    // Open Groups
    fun getAllOpenGroups(): Map<Long, OpenGroup>
    fun getOpenGroup(threadId: Long): OpenGroup?
    fun getOpenGroup(address: Address): OpenGroup?
    suspend fun addOpenGroup(urlAsString: String)
    fun setOpenGroupServerMessageID(messageID: MessageId, serverID: Long, threadID: Long)
    fun getOpenGroup(room: String, server: String): OpenGroup?

    // Open Group Public Keys
    fun getOpenGroupPublicKey(server: String): String?

    // Open Group Metadata
    fun updateTitle(groupID: String, newValue: String)
    fun updateProfilePicture(groupID: String, newValue: ByteArray)
    fun removeProfilePicture(groupID: String)
    fun hasDownloadedProfilePicture(groupID: String): Boolean
    fun setUserCount(room: String, server: String, newValue: Int)

    // Last Message Server ID
    fun getLastMessageServerID(room: String, server: String): Long?
    fun setLastMessageServerID(room: String, server: String, newValue: Long)

    // Last Deletion Server ID
    fun getLastDeletionServerID(room: String, server: String): Long?
    fun setLastDeletionServerID(room: String, server: String, newValue: Long)

    // Message Handling
    fun isDuplicateMessage(timestamp: Long): Boolean
    fun getReceivedMessageTimestamps(): Set<Long>
    fun addReceivedMessageTimestamp(timestamp: Long)
    fun removeReceivedMessageTimestamps(timestamps: Set<Long>)
    fun getAttachmentsForMessage(mmsMessageId: Long): List<DatabaseAttachment>
    fun getMessageBy(timestamp: Long, author: String): MessageRecord?
    fun updateSentTimestamp(messageId: MessageId, newTimestamp: Long)
    fun markAsResyncing(messageId: MessageId)
    fun markAsSyncing(messageId: MessageId)
    fun markAsSending(messageId: MessageId)
    fun markAsSent(messageId: MessageId)
    fun markAsSyncFailed(messageId: MessageId, error: Exception)
    fun markAsSentFailed(messageId: MessageId, error: Exception)
    fun clearErrorMessage(messageID: MessageId)
    fun setMessageServerHash(messageId: MessageId, serverHash: String)

    // Legacy Closed Groups
    fun getGroup(groupID: String): GroupRecord?
    fun createGroup(groupID: String, title: String?, members: List<Address>, avatar: SignalServiceAttachmentPointer?, relay: String?, admins: List<Address>, formationTimestamp: Long)
    fun createInitialConfigGroup(groupPublicKey: String, name: String, members: Map<String, Boolean>, formationTimestamp: Long, encryptionKeyPair: ECKeyPair, expirationTimer: Int)
    fun isGroupActive(groupPublicKey: String): Boolean
    fun setActive(groupID: String, value: Boolean)
    fun removeMember(groupID: String, member: Address)
    fun updateMembers(groupID: String, members: List<Address>)
    fun getAllLegacyGroupPublicKeys(): Set<String>
    fun getAllActiveClosedGroupPublicKeys(): Set<String>
    fun addClosedGroupPublicKey(groupPublicKey: String)
    fun removeClosedGroupPublicKey(groupPublicKey: String)
    fun addClosedGroupEncryptionKeyPair(encryptionKeyPair: ECKeyPair, groupPublicKey: String, timestamp: Long)
    fun removeAllClosedGroupEncryptionKeyPairs(groupPublicKey: String)

    fun insertOutgoingInfoMessage(context: Context, groupID: String, type: SignalServiceGroup.Type, name: String,
        members: Collection<String>, admins: Collection<String>, threadID: Long, sentTimestamp: Long): Long?
    fun isLegacyClosedGroup(publicKey: String): Boolean
    fun getClosedGroupEncryptionKeyPairs(groupPublicKey: String): MutableList<ECKeyPair>
    fun getLatestClosedGroupEncryptionKeyPair(groupPublicKey: String): ECKeyPair?
    fun updateFormationTimestamp(groupID: String, formationTimestamp: Long)
    fun updateTimestampUpdated(groupID: String, updatedTimestamp: Long)

    // Closed Groups
    fun getMembers(groupPublicKey: String): List<LibSessionGroupMember>
    fun getClosedGroupDisplayInfo(groupAccountId: String): GroupDisplayInfo?
    fun insertGroupInfoChange(message: GroupUpdated, closedGroup: AccountId)
    fun insertGroupInfoLeaving(closedGroup: AccountId)
    fun insertGroupInfoErrorQuit(closedGroup: AccountId)
    fun insertGroupInviteControlMessage(
        sentTimestamp: Long,
        senderPublicKey: String,
        senderName: String?,
        closedGroup: AccountId,
        groupName: String
    )

    fun deleteGroupInfoMessages(groupId: AccountId, kind: Class<out UpdateMessageData.Kind>)

    // Groups
    fun getAllGroups(includeInactive: Boolean): List<GroupRecord>

    // Thread
    fun getOrCreateThreadIdFor(address: Address): Long
    fun getThreadId(address: Address): Long?
    fun getThreadIdForMms(mmsId: Long): Long
    fun getLastUpdated(threadID: Long): Long
    fun trimThreadBefore(threadID: Long, timestamp: Long)
    fun getMessageCount(threadID: Long): Long
    fun getTotalPinned(): Int
    fun setPinned(address: Address, isPinned: Boolean)
    fun isRead(threadId: Long) : Boolean
    fun setThreadCreationDate(threadId: Long, newDate: Long)
    fun getLastLegacyRecipient(threadRecipient: String): String?
    fun setLastLegacyRecipient(threadRecipient: String, senderRecipient: String?)
    fun clearMessages(threadID: Long, fromUser: Address? = null): Boolean
    fun clearMedia(threadID: Long, fromUser: Address? = null): Boolean

    // Contacts
    fun getRecipientForThread(threadId: Long): Recipient?
    fun setAutoDownloadAttachments(recipient: Address, shouldAutoDownloadAttachments: Boolean)

    // Attachments
    fun getAttachmentDataUri(attachmentId: AttachmentId): Uri
    fun getAttachmentThumbnailUri(attachmentId: AttachmentId): Uri

    // Message Handling
    /**
     * Returns the ID of the `TSIncomingMessage` that was constructed.
     */
    fun persist(message: VisibleMessage, quotes: QuoteModel?, linkPreview: List<LinkPreview?>, fromGroup: Address.GroupLike?, attachments: List<Attachment>, runThreadUpdate: Boolean): MessageId?
    fun markConversationAsRead(threadId: Long, lastSeenTime: Long, force: Boolean = false)
    fun markConversationAsUnread(threadId: Long)
    fun getLastSeen(threadId: Long): Long
    fun ensureMessageHashesAreSender(hashes: Set<String>, sender: String, closedGroupId: String): Boolean
    fun updateThread(threadId: Long, unarchive: Boolean)
    fun insertDataExtractionNotificationMessage(senderPublicKey: String, message: DataExtractionNotificationInfoMessage, sentTimestamp: Long)
    fun insertMessageRequestResponseFromYou(threadId: Long)
    fun insertCallMessage(senderPublicKey: String, callMessageType: CallMessageType, sentTimestamp: Long)
    fun conversationHasOutgoing(userPublicKey: String): Boolean
    fun deleteMessagesByHash(threadId: Long, hashes: List<String>)
    fun deleteMessagesByUser(threadId: Long, userSessionId: String)
    fun clearAllMessages(threadId: Long): List<String?>

    // Last Inbox Message Id
    fun getLastInboxMessageId(server: String): Long?
    fun setLastInboxMessageId(server: String, messageId: Long)

    // Last Outbox Message Id
    fun getLastOutboxMessageId(server: String): Long?
    fun setLastOutboxMessageId(server: String, messageId: Long)

    /**
     * Add reaction to a message that has the timestamp given by [reaction]. This is less than
     * ideal because timestamp it not a very good identifier for a message, but it is the best we can do
     * if the swarm doesn't give us anything else. [threadId] will help narrow down the message.
     */
    fun addReaction(threadId: Long, reaction: Reaction, messageSender: String, notifyUnread: Boolean)

    /**
     * Add reaction to a specific message. This is preferable to the timestamp lookup.
     */
    fun addReaction(messageId: MessageId, reaction: Reaction, messageSender: String)

    /**
     * Add reactions into the database. If [replaceAll] is true,
     * it will remove all existing reactions that belongs to the same message(s).
     */
    fun addReactions(reactions: Map<MessageId, List<ReactionRecord>>, replaceAll: Boolean, notifyUnread: Boolean)
    fun removeReaction(emoji: String, messageTimestamp: Long, threadId: Long, author: String, notifyUnread: Boolean)
    fun updateReactionIfNeeded(message: Message, sender: String, openGroupSentTimestamp: Long)
    fun deleteReactions(messageId: MessageId)
    fun deleteReactions(messageIds: List<Long>, mms: Boolean)
    fun setBlocked(recipients: Iterable<Address>, isBlocked: Boolean)
    fun setExpirationConfiguration(address: Address, expiryMode: ExpiryMode)

    // Shared configs
    fun canPerformConfigChange(variant: String, publicKey: String, changeTimestampMs: Long): Boolean
    fun isCheckingCommunityRequests(): Boolean
}
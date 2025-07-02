package org.session.libsession.database

import android.content.Context
import android.net.Uri
import network.loki.messenger.libsession_util.util.KeyPair
import org.session.libsession.messaging.BlindedIdMapping
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.Job
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.visible.Attachment
import org.session.libsession.messaging.messages.visible.Profile
import org.session.libsession.messaging.messages.visible.Reaction
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.GroupMember
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
import org.session.libsession.utilities.recipients.Recipient.RecipientSettings
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import network.loki.messenger.libsession_util.util.Contact as LibSessionContact
import network.loki.messenger.libsession_util.util.GroupMember as LibSessionGroupMember

interface StorageProtocol {

    // General
    fun getUserPublicKey(): String?
    fun getUserED25519KeyPair(): KeyPair?
    fun getUserX25519KeyPair(): ECKeyPair
    fun getUserBlindedAccountId(serverPublicKey: String): AccountId?
    fun getUserProfile(): Profile
    fun setProfilePicture(recipient: Recipient, newProfilePicture: String?, newProfileKey: ByteArray?)
    fun setBlocksCommunityMessageRequests(recipient: Recipient, blocksMessageRequests: Boolean)
    fun setUserProfilePicture(newProfilePicture: String?, newProfileKey: ByteArray?)
    fun clearUserPic(clearConfig: Boolean = true)
    // Signal
    fun getOrGenerateRegistrationID(): Int

    // Jobs
    fun persistJob(job: Job)
    fun markJobAsSucceeded(jobId: String)
    fun markJobAsFailedPermanently(jobId: String)
    fun getAllPendingJobs(vararg types: String): Map<String,Job?>
    fun getAttachmentUploadJob(attachmentID: Long): AttachmentUploadJob?
    fun getMessageSendJob(messageSendJobID: String): MessageSendJob?
    fun getMessageReceiveJob(messageReceiveJobID: String): Job?
    fun getGroupAvatarDownloadJob(server: String, room: String, imageId: String?): Job?
    fun resumeMessageSendJobIfNeeded(messageSendJobID: String)
    fun isJobCanceled(job: Job): Boolean
    fun cancelPendingMessageSendJobs(threadID: Long)

    // Authorization
    fun getAuthToken(room: String, server: String): String?
    fun setAuthToken(room: String, server: String, newValue: String)
    fun removeAuthToken(room: String, server: String)

    // Servers
    fun setServerCapabilities(server: String, capabilities: List<String>)
    fun getServerCapabilities(server: String): List<String>

    // Open Groups
    fun getAllOpenGroups(): Map<Long, OpenGroup>
    fun updateOpenGroup(openGroup: OpenGroup)
    fun getOpenGroup(threadId: Long): OpenGroup?
    suspend fun addOpenGroup(urlAsString: String)
    fun onOpenGroupAdded(server: String, room: String)
    fun hasBackgroundGroupAddJob(groupJoinUrl: String): Boolean
    fun setOpenGroupServerMessageID(messageID: MessageId, serverID: Long, threadID: Long)
    fun getOpenGroup(room: String, server: String): OpenGroup?
    fun setGroupMemberRoles(members: List<GroupMember>)

    // Open Group Public Keys
    fun getOpenGroupPublicKey(server: String): String?
    fun setOpenGroupPublicKey(server: String, newValue: String)

    // Open Group Metadata
    fun updateTitle(groupID: String, newValue: String)
    fun updateProfilePicture(groupID: String, newValue: ByteArray)
    fun removeProfilePicture(groupID: String)
    fun hasDownloadedProfilePicture(groupID: String): Boolean
    fun setUserCount(room: String, server: String, newValue: Int)

    // Last Message Server ID
    fun getLastMessageServerID(room: String, server: String): Long?
    fun setLastMessageServerID(room: String, server: String, newValue: Long)
    fun removeLastMessageServerID(room: String, server: String)

    // Last Deletion Server ID
    fun getLastDeletionServerID(room: String, server: String): Long?
    fun setLastDeletionServerID(room: String, server: String, newValue: Long)
    fun removeLastDeletionServerID(room: String, server: String)

    // Message Handling
    fun isDuplicateMessage(timestamp: Long): Boolean
    fun getReceivedMessageTimestamps(): Set<Long>
    fun addReceivedMessageTimestamp(timestamp: Long)
    fun removeReceivedMessageTimestamps(timestamps: Set<Long>)
    fun getAttachmentsForMessage(mmsMessageId: Long): List<DatabaseAttachment>
    fun getMessageBy(timestamp: Long, author: String): MessageRecord?
    fun updateSentTimestamp(messageId: MessageId, openGroupSentTimestamp: Long, threadId: Long)
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
    fun updateGroupConfig(groupPublicKey: String)
    fun isGroupActive(groupPublicKey: String): Boolean
    fun setActive(groupID: String, value: Boolean)
    fun getZombieMembers(groupID: String): Set<String>
    fun removeMember(groupID: String, member: Address)
    fun updateMembers(groupID: String, members: List<Address>)
    fun setZombieMembers(groupID: String, members: List<Address>)
    fun getAllLegacyGroupPublicKeys(): Set<String>
    fun getAllActiveClosedGroupPublicKeys(): Set<String>
    fun addClosedGroupPublicKey(groupPublicKey: String)
    fun removeClosedGroupPublicKey(groupPublicKey: String)
    fun addClosedGroupEncryptionKeyPair(encryptionKeyPair: ECKeyPair, groupPublicKey: String, timestamp: Long)
    fun removeAllClosedGroupEncryptionKeyPairs(groupPublicKey: String)
    fun insertIncomingInfoMessage(context: Context, senderPublicKey: String, groupID: String, type: SignalServiceGroup.Type,
        name: String, members: Collection<String>, admins: Collection<String>, sentTimestamp: Long): Long?

    fun updateInfoMessage(context: Context, messageId: Long, groupID: String, type: SignalServiceGroup.Type, name: String, members: Collection<String>)

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

    fun updateGroupInfoChange(messageId: Long, newType: UpdateMessageData.Kind)
    fun deleteGroupInfoMessages(groupId: AccountId, kind: Class<out UpdateMessageData.Kind>)

    // Groups
    fun getAllGroups(includeInactive: Boolean): List<GroupRecord>

    // Settings
    fun setProfileSharing(address: Address, value: Boolean)

    // Thread
    fun getOrCreateThreadIdFor(address: Address): Long
    fun getThreadIdFor(publicKey: String, groupPublicKey: String?, openGroupID: String?, createThread: Boolean): Long?
    fun getThreadId(publicKeyOrOpenGroupID: String): Long?
    fun getThreadId(openGroup: OpenGroup): Long?
    fun getThreadId(address: Address): Long?
    fun getThreadId(recipient: Recipient): Long?
    fun getThreadIdForMms(mmsId: Long): Long
    fun getLastUpdated(threadID: Long): Long
    fun trimThread(threadID: Long, threadLimit: Int)
    fun trimThreadBefore(threadID: Long, timestamp: Long)
    fun getMessageCount(threadID: Long): Long
    fun getTotalPinned(): Int
    fun setPinned(threadID: Long, isPinned: Boolean)
    fun isPinned(threadID: Long): Boolean
    fun deleteConversation(threadID: Long)
    fun setThreadCreationDate(threadId: Long, newDate: Long)
    fun getLastLegacyRecipient(threadRecipient: String): String?
    fun setLastLegacyRecipient(threadRecipient: String, senderRecipient: String?)
    fun clearMessages(threadID: Long, fromUser: Address? = null): Boolean
    fun clearMedia(threadID: Long, fromUser: Address? = null): Boolean

    // Contacts
    fun getContactWithAccountID(accountID: String): Contact?
    fun getAllContacts(): Set<Contact>
    fun setContact(contact: Contact)
    fun deleteContactAndSyncConfig(accountId: String)
    fun getRecipientForThread(threadId: Long): Recipient?
    fun getRecipientSettings(address: Address): RecipientSettings?
    fun syncLibSessionContacts(contacts: List<LibSessionContact>, timestamp: Long?)
    fun hasAutoDownloadFlagBeenSet(recipient: Recipient): Boolean
    fun shouldAutoDownloadAttachments(recipient: Recipient): Boolean
    fun setAutoDownloadAttachments(recipient: Recipient, shouldAutoDownloadAttachments: Boolean)

    // Attachments
    fun getAttachmentDataUri(attachmentId: AttachmentId): Uri
    fun getAttachmentThumbnailUri(attachmentId: AttachmentId): Uri

    // Message Handling
    /**
     * Returns the ID of the `TSIncomingMessage` that was constructed.
     */
    fun persist(message: VisibleMessage, quotes: QuoteModel?, linkPreview: List<LinkPreview?>, groupPublicKey: String?, openGroupID: String?, attachments: List<Attachment>, runThreadUpdate: Boolean): MessageId?
    fun markConversationAsRead(threadId: Long, lastSeenTime: Long, force: Boolean = false)
    fun getLastSeen(threadId: Long): Long
    fun ensureMessageHashesAreSender(hashes: Set<String>, sender: String, closedGroupId: String): Boolean
    fun updateThread(threadId: Long, unarchive: Boolean)
    fun insertDataExtractionNotificationMessage(senderPublicKey: String, message: DataExtractionNotificationInfoMessage, sentTimestamp: Long)
    fun insertMessageRequestResponseFromContact(response: MessageRequestResponse)
    fun insertMessageRequestResponseFromYou(threadId: Long)
    fun setRecipientApproved(recipient: Recipient, approved: Boolean)
    fun getRecipientApproved(address: Address): Boolean
    fun setRecipientApprovedMe(recipient: Recipient, approvedMe: Boolean)
    fun insertCallMessage(senderPublicKey: String, callMessageType: CallMessageType, sentTimestamp: Long)
    fun conversationHasOutgoing(userPublicKey: String): Boolean
    fun deleteMessagesByHash(threadId: Long, hashes: List<String>)
    fun deleteMessagesByUser(threadId: Long, userSessionId: String)
    fun clearAllMessages(threadId: Long): List<String?>

    // Last Inbox Message Id
    fun getLastInboxMessageId(server: String): Long?
    fun setLastInboxMessageId(server: String, messageId: Long)
    fun removeLastInboxMessageId(server: String)

    // Last Outbox Message Id
    fun getLastOutboxMessageId(server: String): Long?
    fun setLastOutboxMessageId(server: String, messageId: Long)
    fun removeLastOutboxMessageId(server: String)
    fun getOrCreateBlindedIdMapping(blindedId: String, server: String, serverPublicKey: String, fromOutbox: Boolean = false): BlindedIdMapping

    /**
     * Add reaction to a message that has the timestamp given by [reaction]. This is less than
     * ideal because timestamp it not a very good identifier for a message, but it is the best we can do
     * if the swarm doesn't give us anything else. [threadId] will help narrow down the message.
     */
    fun addReaction(threadId: Long, reaction: Reaction, messageSender: String, notifyUnread: Boolean)

    /**
     * Add reaction to a specific message. This is preferable to the timestamp lookup.
     */
    fun addReaction(messageId: MessageId, reaction: Reaction, messageSender: String, notifyUnread: Boolean)

    /**
     * Add reactions into the database. If [replaceAll] is true,
     * it will remove all existing reactions that belongs to the same message(s).
     */
    fun addReactions(reactions: Map<MessageId, List<ReactionRecord>>, replaceAll: Boolean, notifyUnread: Boolean)
    fun removeReaction(emoji: String, messageTimestamp: Long, threadId: Long, author: String, notifyUnread: Boolean)
    fun updateReactionIfNeeded(message: Message, sender: String, openGroupSentTimestamp: Long)
    fun deleteReactions(messageId: MessageId)
    fun deleteReactions(messageIds: List<Long>, mms: Boolean)
    fun setBlocked(recipients: Iterable<Recipient>, isBlocked: Boolean, fromConfigUpdate: Boolean = false)
    fun blockedContacts(): List<Recipient>
    fun getExpirationConfiguration(threadId: Long): ExpirationConfiguration?
    fun setExpirationConfiguration(config: ExpirationConfiguration)
    fun getExpiringMessages(messageIds: List<Long> = emptyList()): List<Pair<Long, Long>>
    fun updateDisappearingState(
        messageSender: String,
        threadID: Long,
        disappearingState: Recipient.DisappearingState
    )

    // Shared configs
    fun conversationInConfig(publicKey: String?, groupPublicKey: String?, openGroupId: String?, visibleOnly: Boolean): Boolean
    fun canPerformConfigChange(variant: String, publicKey: String, changeTimestampMs: Long): Boolean
    fun isCheckingCommunityRequests(): Boolean
}
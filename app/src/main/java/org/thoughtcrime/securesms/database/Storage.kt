package org.thoughtcrime.securesms.database

import android.content.Context
import android.net.Uri
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_PINNED
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.MutableConversationVolatileConfig
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.Conversation
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.KeyPair
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.Job
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.ProfileUpdateHandler
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.signal.IncomingEncryptedMessage
import org.session.libsession.messaging.messages.signal.IncomingGroupMessage
import org.session.libsession.messaging.messages.signal.IncomingMediaMessage
import org.session.libsession.messaging.messages.signal.IncomingTextMessage
import org.session.libsession.messaging.messages.signal.OutgoingGroupMediaMessage
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage
import org.session.libsession.messaging.messages.visible.Attachment
import org.session.libsession.messaging.messages.visible.Profile
import org.session.libsession.messaging.messages.visible.Reaction
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.GroupDisplayInfo
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.isCommunity
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.upsertContact
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.KeyHelper
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.crypto.KeyPairUtilities
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.util.FilenameUtils
import org.thoughtcrime.securesms.util.SessionMetaProtocol
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import network.loki.messenger.libsession_util.util.GroupMember as LibSessionGroupMember

private const val TAG = "Storage"

@Singleton
open class Storage @Inject constructor(
    @ApplicationContext context: Context,
    helper: Provider<SQLCipherOpenHelper>,
    private val configFactory: ConfigFactory,
    private val jobDatabase: SessionJobDatabase,
    private val threadDatabase: ThreadDatabase,
    private val recipientDatabase: RecipientSettingsDatabase,
    private val attachmentDatabase: AttachmentDatabase,
    private val draftDatabase: DraftDatabase,
    private val lokiAPIDatabase: LokiAPIDatabase,
    private val groupDatabase: GroupDatabase,
    private val lokiMessageDatabase: LokiMessageDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val groupMemberDatabase: GroupMemberDatabase,
    private val reactionDatabase: ReactionDatabase,
    private val lokiThreadDatabase: LokiThreadDatabase,
    private val notificationManager: MessageNotifier,
    private val messageDataProvider: MessageDataProvider,
    private val clock: SnodeClock,
    private val preferences: TextSecurePreferences,
    private val openGroupManager: Lazy<OpenGroupManager>,
    private val recipientRepository: RecipientRepository,
    private val profileUpdateHandler: ProfileUpdateHandler,
) : Database(context, helper), StorageProtocol {

    override fun getUserPublicKey(): String? { return preferences.getLocalNumber() }

    override fun getUserX25519KeyPair(): ECKeyPair { return lokiAPIDatabase.getUserX25519KeyPair() }

    override fun getUserED25519KeyPair(): KeyPair? { return KeyPairUtilities.getUserED25519KeyPair(context) }

    override fun getUserBlindedAccountId(serverPublicKey: String): AccountId? {
        val myId = getUserPublicKey() ?: return null
        return AccountId(BlindKeyAPI.blind15Ids(myId, serverPublicKey).first())
    }

    override fun getUserProfile(): Profile {
        return configFactory.withUserConfigs { configs ->
            val pic = configs.userProfile.getPic()
            Profile(
                displayName = configs.userProfile.getName(),
                profilePictureURL = pic.url.takeIf { it.isNotBlank() },
                profileKey = pic.key.data.takeIf { pic.url.isNotBlank() },
                profileUpdated = preferences.lastProfileUpdated,
            )
        }
    }

    override fun getOrGenerateRegistrationID(): Int {
        var registrationID = TextSecurePreferences.getLocalRegistrationId(context)
        if (registrationID == 0) {
            registrationID = KeyHelper.generateRegistrationId(false)
            TextSecurePreferences.setLocalRegistrationId(context, registrationID)
        }
        return registrationID
    }

    override fun getAttachmentsForMessage(mmsMessageId: Long): List<DatabaseAttachment> {
        return attachmentDatabase.getAttachmentsForMessage(mmsMessageId)
    }

    override fun getLastSeen(threadId: Long): Long {
        return threadDatabase.getLastSeenAndHasSent(threadId)?.first() ?: 0L
    }

    override fun ensureMessageHashesAreSender(
        hashes: Set<String>,
        sender: String,
        closedGroupId: String
    ): Boolean {
        val threadId = getThreadId(fromSerialized(closedGroupId))!!
        val senderIsMe = sender == getUserPublicKey()

        val info = lokiMessageDatabase.getSendersForHashes(threadId, hashes)

        return if (senderIsMe) info.all { it.isOutgoing } else info.all { it.sender == sender }
    }

    override fun deleteMessagesByHash(threadId: Long, hashes: List<String>) {
        for (info in lokiMessageDatabase.getSendersForHashes(threadId, hashes.toSet())) {
            messageDataProvider.deleteMessage(info.messageId)
            if (!info.isOutgoing) {
                notificationManager.updateNotification(context)
            }
        }
    }

    override fun deleteMessagesByUser(threadId: Long, userSessionId: String) {
        val userMessages = mmsSmsDatabase.getUserMessages(threadId, userSessionId)
        val (mmsMessages, smsMessages) = userMessages.partition { it.isMms }
        if (mmsMessages.isNotEmpty()) {
            messageDataProvider.deleteMessages(mmsMessages.map(MessageRecord::id), isSms = false)
        }
        if (smsMessages.isNotEmpty()) {
            messageDataProvider.deleteMessages(smsMessages.map(MessageRecord::id), isSms = true)
        }
    }

    override fun clearAllMessages(threadId: Long): List<String?> {
        val messages = mmsSmsDatabase.getAllMessagesWithHash(threadId)
        val (mmsMessages, smsMessages) = messages.partition { it.first.isMms }
        if (mmsMessages.isNotEmpty()) {
            messageDataProvider.deleteMessages(mmsMessages.map{ it.first.id }, isSms = false)
        }
        if (smsMessages.isNotEmpty()) {
            messageDataProvider.deleteMessages(smsMessages.map{ it.first.id }, isSms = true)
        }

        return messages.map { it.second } // return the message hashes
    }

    override fun markConversationAsRead(threadId: Long, lastSeenTime: Long, force: Boolean) {
        val threadDb = threadDatabase
        getRecipientForThread(threadId)?.let { recipient ->
            val currentLastRead = threadDb.getLastSeenAndHasSent(threadId).first()
            // don't set the last read in the volatile if we didn't set it in the DB
            if (!threadDb.markAllAsRead(threadId, lastSeenTime, force) && !force) return

            // don't process configs for inbox recipients
            if (recipient.isCommunityInboxRecipient) return

            configFactory.withMutableUserConfigs { configs ->
                val config = configs.convoInfoVolatile
                val convo = getConvo(recipient, config)  ?: return@withMutableUserConfigs
                convo.lastRead = lastSeenTime

                if(convo.unread){
                    convo.unread = lastSeenTime < currentLastRead
                }

                config.set(convo)
            }
        }
    }

    override fun markConversationAsUnread(threadId: Long) {
        getRecipientForThread(threadId)?.let { recipient ->
            // don't process configs for inbox recipients
            if (recipient.isCommunityInboxRecipient) return

            configFactory.withMutableUserConfigs { configs ->
                val config = configs.convoInfoVolatile
                val convo = getConvo(recipient, config) ?: return@withMutableUserConfigs

                convo.unread = true
                config.set(convo)
            }
        }
    }

    private fun getConvo(recipient: Recipient, config: MutableConversationVolatileConfig) : Conversation? {
        return when (recipient.address) {
            // recipient closed group
            is Address.LegacyGroup -> config.getOrConstructLegacyGroup(recipient.address.groupPublicKeyHex)
            is Address.Group -> config.getOrConstructClosedGroup(recipient.address.accountId.hexString)
            // recipient is open group
            is Address.Community -> {
                val og = recipient.data as? RecipientData.Community ?: return null
                config.getOrConstructCommunity(
                    baseUrl = og.openGroup.server,
                    room = og.openGroup.room,
                    pubKeyHex = og.openGroup.publicKey,
                )
            }
            is Address.CommunityBlindedId -> {
                config.getOrConstructedBlindedOneToOne(recipient.address.blindedId.blindedId.hexString)
            }
            // otherwise recipient is one to one
            is Address.Standard -> {
                config.getOrConstructOneToOne(recipient.address.accountId.hexString)
            }
            else -> throw NullPointerException("Weren't expecting to have a convo with address ${recipient.address}")
        }
    }

    override fun updateThread(threadId: Long, unarchive: Boolean) {
        val threadDb = threadDatabase
        threadDb.update(threadId, unarchive)
    }

    override fun persist(
        message: VisibleMessage,
        quotes: QuoteModel?,
        linkPreview: List<LinkPreview?>,
        fromGroup: Address.GroupLike?,
        attachments: List<Attachment>,
        runThreadUpdate: Boolean
    ): MessageId? {
        val messageID: MessageId?
        val senderAddress = fromSerialized(message.sender!!)
        val isUserSender = (message.sender!! == getUserPublicKey())
        val isUserBlindedSender = message.threadID?.takeIf { it >= 0 }?.let(::getOpenGroup)?.publicKey
            ?.let {
                BlindKeyAPI.sessionIdMatchesBlindedId(
                    sessionId = getUserPublicKey()!!,
                    blindedId = message.sender!!,
                    serverPubKey = it
                )
            } ?: false

        val targetAddress = if ((isUserSender || isUserBlindedSender) && !message.syncTarget.isNullOrEmpty()) {
            message.syncTarget!!.toAddress()
        } else fromGroup ?: senderAddress

        if (message.threadID == null && !targetAddress.isCommunity) {
            // open group recipients should explicitly create threads
            message.threadID = getOrCreateThreadIdFor(targetAddress)
        }
        val expiryMode = message.expiryMode
        val expiresInMillis = expiryMode.expiryMillis
        val expireStartedAt = if (expiryMode is ExpiryMode.AfterSend) message.sentTimestamp!! else 0


        if (message.isMediaMessage() || attachments.isNotEmpty()) {

            // Sanitise attachments with missing names
            for (attachment in attachments.filter { it.filename.isNullOrEmpty() }) {

                // Unfortunately we have multiple Attachment classes, but only `SignalAttachment` has the `isVoiceNote` property which we can
                // use to differentiate between an audio-file with no filename and a voice-message with no file-name, so we convert to that
                // and pass it through.
                val signalAttachment = attachment.toSignalAttachment()
                attachment.filename = FilenameUtils.getFilenameFromUri(context, Uri.parse(attachment.url), attachment.contentType, signalAttachment)
            }

            val quote: Optional<QuoteModel> = if (quotes != null) Optional.of(quotes) else Optional.absent()
            val linkPreviews: Optional<List<LinkPreview>> = if (linkPreview.isEmpty()) Optional.absent() else Optional.of(linkPreview.mapNotNull { it!! })
            val insertResult = if (isUserSender || isUserBlindedSender) {
                val pointers = attachments.mapNotNull {
                    it.toSignalAttachment()
                }

                val mediaMessage = OutgoingMediaMessage.from(
                    message,
                    targetAddress,
                    pointers,
                    quote.orNull(),
                    linkPreviews.orNull()?.firstOrNull(),
                    expiresInMillis,
                    expireStartedAt
                )
                mmsDatabase.insertSecureDecryptedMessageOutbox(mediaMessage, message.threadID ?: -1, message.sentTimestamp!!, runThreadUpdate)
            } else {
                // It seems like we have replaced SignalServiceAttachment with SessionServiceAttachment
                val signalServiceAttachments = attachments.mapNotNull {
                    it.toSignalPointer()
                }
                val mediaMessage = IncomingMediaMessage.from(message, senderAddress, expiresInMillis, expireStartedAt, Optional.fromNullable(fromGroup), signalServiceAttachments, quote, linkPreviews)
                mmsDatabase.insertSecureDecryptedMessageInbox(mediaMessage, message.threadID!!, message.receivedTimestamp ?: 0, runThreadUpdate)
            }

            messageID = insertResult.orNull()?.messageId?.let { MessageId(it, mms = true) }

        } else {
            val isOpenGroupInvitation = (message.openGroupInvitation != null)

            val insertResult = if (isUserSender || isUserBlindedSender) {
                val textMessage = if (isOpenGroupInvitation) OutgoingTextMessage.fromOpenGroupInvitation(message.openGroupInvitation, targetAddress, message.sentTimestamp, expiresInMillis, expireStartedAt)
                else OutgoingTextMessage.from(message, targetAddress, expiresInMillis, expireStartedAt)
                smsDatabase.insertMessageOutbox(message.threadID ?: -1, textMessage, message.sentTimestamp!!, runThreadUpdate)
            } else {
                val textMessage = if (isOpenGroupInvitation) IncomingTextMessage.fromOpenGroupInvitation(message.openGroupInvitation, senderAddress, message.sentTimestamp, expiresInMillis, expireStartedAt)
                else IncomingTextMessage.from(message, senderAddress, Optional.fromNullable(fromGroup), expiresInMillis, expireStartedAt)
                val encrypted = IncomingEncryptedMessage(textMessage, textMessage.messageBody)
                smsDatabase.insertMessageInbox(encrypted, message.receivedTimestamp ?: 0, runThreadUpdate)
            }
            messageID = insertResult.orNull()?.messageId?.let { MessageId(it, mms = false) }
        }

        message.serverHash?.let { serverHash ->
            messageID?.let { id ->
                lokiMessageDatabase.setMessageServerHash(id, serverHash)
            }
        }
        return messageID
    }

    override fun persistJob(job: Job) {
        jobDatabase.persistJob(job)
    }

    override fun markJobAsSucceeded(jobId: String) {
        jobDatabase.markJobAsSucceeded(jobId)
    }

    override fun markJobAsFailedPermanently(jobId: String) {
        jobDatabase.markJobAsFailedPermanently(jobId)
    }

    override fun getAllPendingJobs(vararg types: String): Map<String, Job?> {
        return jobDatabase.getAllJobs(*types)
    }

    override fun getAttachmentUploadJob(attachmentID: Long): AttachmentUploadJob? {
        return jobDatabase.getAttachmentUploadJob(attachmentID)
    }

    override fun getMessageSendJob(messageSendJobID: String): MessageSendJob? {
        return jobDatabase.getMessageSendJob(messageSendJobID)
    }

    override fun resumeMessageSendJobIfNeeded(messageSendJobID: String) {
        val job = jobDatabase.getMessageSendJob(messageSendJobID) ?: return
        JobQueue.shared.resumePendingSendMessage(job)
    }

    override fun isJobCanceled(job: Job): Boolean {
        return jobDatabase.isJobCanceled(job)
    }

    override fun cancelPendingMessageSendJobs(threadID: Long) {
        val jobDb = jobDatabase
        jobDb.cancelPendingMessageSendJobs(threadID)
    }

    override fun getAuthToken(room: String, server: String): String? {
        val id = "$server.$room"
        return lokiAPIDatabase.getAuthToken(id)
    }


    override fun canPerformConfigChange(variant: String, publicKey: String, changeTimestampMs: Long): Boolean {
        return configFactory.canPerformChange(variant, publicKey, changeTimestampMs)
    }

    override fun isCheckingCommunityRequests(): Boolean {
        return configFactory.withUserConfigs { it.userProfile.getCommunityMessageRequests() }
    }

    override fun setAuthToken(room: String, server: String, newValue: String) {
        val id = "$server.$room"
        lokiAPIDatabase.setAuthToken(id, newValue)
    }

    override fun removeAuthToken(room: String, server: String) {
        val id = "$server.$room"
        lokiAPIDatabase.setAuthToken(id, null)
    }

    override fun getOpenGroup(threadId: Long): OpenGroup? {
        return lokiThreadDatabase.getOpenGroupChat(threadId)
    }

    override fun getOpenGroup(address: Address): OpenGroup? {
        return getThreadId(address)?.let(lokiThreadDatabase::getOpenGroupChat)
    }

    override fun getOpenGroupPublicKey(server: String): String? {
        return configFactory.withUserConfigs { it.userGroups.allCommunityInfo() }
            .firstOrNull { it.community.baseUrl == server }
            ?.community
            ?.pubKeyHex
    }

    override fun getLastMessageServerID(room: String, server: String): Long? {
        return lokiAPIDatabase.getLastMessageServerID(room, server)
    }

    override fun setLastMessageServerID(room: String, server: String, newValue: Long) {
        lokiAPIDatabase.setLastMessageServerID(room, server, newValue)
    }

    override fun getLastDeletionServerID(room: String, server: String): Long? {
        return lokiAPIDatabase.getLastDeletionServerID(room, server)
    }

    override fun setLastDeletionServerID(room: String, server: String, newValue: Long) {
        lokiAPIDatabase.setLastDeletionServerID(room, server, newValue)
    }

    override fun setUserCount(room: String, server: String, newValue: Int) {
        lokiAPIDatabase.setUserCount(room, server, newValue)
    }

    override fun setOpenGroupServerMessageID(messageID: MessageId, serverID: Long, threadID: Long) {
        lokiMessageDatabase.setServerID(messageID, serverID)
        lokiMessageDatabase.setOriginalThreadID(messageID.id, serverID, threadID)
    }

    override fun getOpenGroup(room: String, server: String): OpenGroup? {
        return getAllOpenGroups().values.firstOrNull { it.server == server && it.room == room }
    }

    override fun isDuplicateMessage(timestamp: Long): Boolean {
        return getReceivedMessageTimestamps().contains(timestamp)
    }

    override fun updateTitle(groupID: String, newValue: String) {
        groupDatabase.updateTitle(groupID, newValue)
    }

    override fun updateProfilePicture(groupID: String, newValue: ByteArray) {
        groupDatabase.updateProfilePicture(groupID, newValue)
    }

    override fun removeProfilePicture(groupID: String) {
        groupDatabase.removeProfilePicture(groupID)
    }

    override fun hasDownloadedProfilePicture(groupID: String): Boolean {
        return groupDatabase.hasDownloadedProfilePicture(groupID)
    }

    override fun getReceivedMessageTimestamps(): Set<Long> {
        return SessionMetaProtocol.getTimestamps()
    }

    override fun addReceivedMessageTimestamp(timestamp: Long) {
        SessionMetaProtocol.addTimestamp(timestamp)
    }

    override fun removeReceivedMessageTimestamps(timestamps: Set<Long>) {
        SessionMetaProtocol.removeTimestamps(timestamps)
    }

    override fun getMessageBy(timestamp: Long, author: String): MessageRecord? {
        val database = mmsSmsDatabase
        val address = fromSerialized(author)
        return database.getMessageFor(timestamp, address)
    }

    override fun updateSentTimestamp(
        messageId: MessageId,
        newTimestamp: Long
    ) {
        if (messageId.mms) {
            mmsDatabase.updateSentTimestamp(messageId.id, newTimestamp)
        } else {
            smsDatabase.updateSentTimestamp(messageId.id, newTimestamp)
        }
    }

    override fun markAsSent(messageId: MessageId) {
        getMmsDatabaseElseSms(messageId.mms).markAsSent(messageId.id, true)
    }

    override fun markAsSyncing(messageId: MessageId) {
        getMmsDatabaseElseSms(messageId.mms).markAsSyncing(messageId.id)
    }

    private fun getMmsDatabaseElseSms(isMms: Boolean) =
        if (isMms) mmsDatabase
        else smsDatabase

    override fun markAsResyncing(messageId: MessageId) {
        getMmsDatabaseElseSms(messageId.mms).markAsResyncing(messageId.id)
    }

    override fun markAsSending(messageId: MessageId) {
        if (messageId.mms) {
            mmsDatabase.markAsSending(messageId.id)
        } else {
            smsDatabase.markAsSending(messageId.id)
        }
    }

    override fun markAsSentFailed(messageId: MessageId, error: Exception) {
        if (messageId.mms) {
            mmsDatabase.markAsSentFailed(messageId.id)
        } else {
            smsDatabase.markAsSentFailed(messageId.id)
        }
        if (error.localizedMessage != null) {
            val message: String
            if (error is OnionRequestAPI.HTTPRequestFailedAtDestinationException && error.statusCode == 429) {
                message = "429: Rate limited."
            } else {
                message = error.localizedMessage!!
            }
            lokiMessageDatabase.setErrorMessage(messageId, message)
        } else {
            lokiMessageDatabase.setErrorMessage(messageId, error.javaClass.simpleName)
        }
    }

    override fun markAsSyncFailed(messageId: MessageId, error: Exception) {
        getMmsDatabaseElseSms(messageId.mms).markAsSyncFailed(messageId.id)

        if (error.localizedMessage != null) {
            val message: String
            if (error is OnionRequestAPI.HTTPRequestFailedAtDestinationException && error.statusCode == 429) {
                message = "429: Rate limited."
            } else {
                message = error.localizedMessage!!
            }
            lokiMessageDatabase.setErrorMessage(messageId, message)
        } else {
            lokiMessageDatabase.setErrorMessage(messageId, error.javaClass.simpleName)
        }
    }

    override fun clearErrorMessage(messageID: MessageId) {
        lokiMessageDatabase.clearErrorMessage(messageID)
    }

    override fun setMessageServerHash(messageId: MessageId, serverHash: String) {
        lokiMessageDatabase.setMessageServerHash(messageId, serverHash)
    }

    override fun getGroup(groupID: String): GroupRecord? {
        val group = groupDatabase.getGroup(groupID)
        return if (group.isPresent) { group.get() } else null
    }

    override fun createGroup(groupID: String, title: String?, members: List<Address>, avatar: SignalServiceAttachmentPointer?, relay: String?, admins: List<Address>, formationTimestamp: Long) {
        groupDatabase.create(groupID, title, members, avatar, relay, admins, formationTimestamp)
    }

    override fun createInitialConfigGroup(groupPublicKey: String, name: String, members: Map<String, Boolean>, formationTimestamp: Long, encryptionKeyPair: ECKeyPair, expirationTimer: Int) {
        configFactory.withMutableUserConfigs {
            val volatiles = it.convoInfoVolatile
            val userGroups = it.userGroups
            if (volatiles.getLegacyClosedGroup(groupPublicKey) != null && userGroups.getLegacyGroupInfo(groupPublicKey) != null) {
                return@withMutableUserConfigs
            }

            val groupVolatileConfig = volatiles.getOrConstructLegacyGroup(groupPublicKey)
            groupVolatileConfig.lastRead = formationTimestamp
            volatiles.set(groupVolatileConfig)
            val groupInfo = GroupInfo.LegacyGroupInfo(
                accountId = groupPublicKey,
                name = name,
                members = members,
                priority = PRIORITY_VISIBLE,
                encPubKey = Bytes((encryptionKeyPair.publicKey as DjbECPublicKey).publicKey),  // 'serialize()' inserts an extra byte
                encSecKey = Bytes(encryptionKeyPair.privateKey.serialize()),
                disappearingTimer = expirationTimer.toLong(),
                joinedAtSecs = (formationTimestamp / 1000L)
            )
            // shouldn't exist, don't use getOrConstruct + copy
            userGroups.set(groupInfo)
        }
    }

    override fun isGroupActive(groupPublicKey: String): Boolean {
        return groupDatabase.getGroup(GroupUtil.doubleEncodeGroupID(groupPublicKey)).orNull()?.isActive == true
    }

    override fun setActive(groupID: String, value: Boolean) {
        groupDatabase.setActive(groupID, value)
    }

    override fun removeMember(groupID: String, member: Address) {
        groupDatabase.removeMember(groupID, member)
    }

    override fun updateMembers(groupID: String, members: List<Address>) {
        groupDatabase.updateMembers(groupID, members)
    }

    override fun insertOutgoingInfoMessage(context: Context, groupID: String, type: SignalServiceGroup.Type, name: String, members: Collection<String>, admins: Collection<String>, threadID: Long, sentTimestamp: Long): Long? {
        val userPublicKey = getUserPublicKey()!!
        val recipient = fromSerialized(groupID)
        val updateData = UpdateMessageData.buildGroupUpdate(type, name, members)?.toJSON() ?: ""
        val infoMessage = OutgoingGroupMediaMessage(
            recipient,
            updateData,
            groupID,
            null,
            sentTimestamp,
            0,
            0,
            true,
            null,
            listOf(),
            listOf(),
            null
        )
        val mmsDB = mmsDatabase
        val mmsSmsDB = mmsSmsDatabase
        if (mmsSmsDB.getMessageFor(sentTimestamp, userPublicKey) != null) {
            Log.w(TAG, "Bailing from insertOutgoingInfoMessage because we believe the message has already been sent!")
            return null
        }
        val infoMessageID = mmsDB.insertMessageOutbox(
            infoMessage,
            threadID,
            false,
            runThreadUpdate = true
        )
        mmsDB.markAsSent(infoMessageID, true)
        return infoMessageID
    }

    override fun isLegacyClosedGroup(publicKey: String): Boolean {
        return lokiAPIDatabase.isClosedGroup(publicKey)
    }

    override fun getClosedGroupEncryptionKeyPairs(groupPublicKey: String): MutableList<ECKeyPair> {
        return lokiAPIDatabase.getClosedGroupEncryptionKeyPairs(groupPublicKey).toMutableList()
    }

    override fun getLatestClosedGroupEncryptionKeyPair(groupPublicKey: String): ECKeyPair? {
        return lokiAPIDatabase.getLatestClosedGroupEncryptionKeyPair(groupPublicKey)
    }

    override fun getAllLegacyGroupPublicKeys(): Set<String> {
        return lokiAPIDatabase.getAllClosedGroupPublicKeys()
    }

    override fun getAllActiveClosedGroupPublicKeys(): Set<String> {
        return lokiAPIDatabase.getAllClosedGroupPublicKeys().filter {
            getGroup(GroupUtil.doubleEncodeGroupID(it))?.isActive == true
        }.toSet()
    }

    override fun addClosedGroupPublicKey(groupPublicKey: String) {
        lokiAPIDatabase.addClosedGroupPublicKey(groupPublicKey)
    }

    override fun removeClosedGroupPublicKey(groupPublicKey: String) {
        lokiAPIDatabase.removeClosedGroupPublicKey(groupPublicKey)
    }

    override fun addClosedGroupEncryptionKeyPair(encryptionKeyPair: ECKeyPair, groupPublicKey: String, timestamp: Long) {
        lokiAPIDatabase.addClosedGroupEncryptionKeyPair(encryptionKeyPair, groupPublicKey, timestamp)
    }

    override fun removeAllClosedGroupEncryptionKeyPairs(groupPublicKey: String) {
        lokiAPIDatabase.removeAllClosedGroupEncryptionKeyPairs(groupPublicKey)
    }

    override fun updateFormationTimestamp(groupID: String, formationTimestamp: Long) {
        groupDatabase
            .updateFormationTimestamp(groupID, formationTimestamp)
    }

    override fun updateTimestampUpdated(groupID: String, updatedTimestamp: Long) {
        groupDatabase
            .updateTimestampUpdated(groupID, updatedTimestamp)
    }

    /**
     * For new closed groups
     */
    override fun getMembers(groupPublicKey: String): List<LibSessionGroupMember> =
        configFactory.withGroupConfigs(AccountId(groupPublicKey)) {
            it.groupMembers.all()
        }

    override fun getClosedGroupDisplayInfo(groupAccountId: String): GroupDisplayInfo? {
        val groupIsAdmin = configFactory.getGroup(AccountId(groupAccountId))?.hasAdminKey() ?: return null

        return configFactory.withGroupConfigs(AccountId(groupAccountId)) { configs ->
            val info = configs.groupInfo
            GroupDisplayInfo(
                id = AccountId(info.id()),
                name = info.getName(),
                profilePic = info.getProfilePic(),
                expiryTimer = info.getExpiryTimer(),
                destroyed = false,
                created = info.getCreated(),
                description = info.getDescription(),
                isUserAdmin = groupIsAdmin
            )
        }
    }

    override fun insertGroupInfoChange(message: GroupUpdated, closedGroup: AccountId) {
        val sentTimestamp = message.sentTimestamp ?: clock.currentTimeMills()
        val senderPublicKey = message.sender
        val groupName = configFactory.withGroupConfigs(closedGroup) { it.groupInfo.getName() }
            ?: configFactory.getGroup(closedGroup)?.name

        val updateData = UpdateMessageData.buildGroupUpdate(message, groupName.orEmpty()) ?: return

        insertUpdateControlMessage(updateData, sentTimestamp, senderPublicKey, closedGroup)
    }

    override fun insertGroupInfoLeaving(closedGroup: AccountId) {
        val sentTimestamp = clock.currentTimeMills()
        val senderPublicKey = getUserPublicKey() ?: return
        val updateData = UpdateMessageData.buildGroupLeaveUpdate(UpdateMessageData.Kind.GroupLeaving)

        insertUpdateControlMessage(updateData, sentTimestamp, senderPublicKey, closedGroup)
    }

    override fun insertGroupInfoErrorQuit(closedGroup: AccountId) {
        val sentTimestamp = clock.currentTimeMills()
        val senderPublicKey = getUserPublicKey() ?: return
        val groupName = configFactory.withGroupConfigs(closedGroup) { it.groupInfo.getName() }
            ?: configFactory.getGroup(closedGroup)?.name
        val updateData = UpdateMessageData.buildGroupLeaveUpdate(UpdateMessageData.Kind.GroupErrorQuit(groupName.orEmpty()))

        insertUpdateControlMessage(updateData, sentTimestamp, senderPublicKey, closedGroup)
    }

    override fun deleteGroupInfoMessages(groupId: AccountId, kind: Class<out UpdateMessageData.Kind>) {
        mmsSmsDatabase.deleteGroupInfoMessage(groupId, kind)
    }

    override fun insertGroupInviteControlMessage(sentTimestamp: Long, senderPublicKey: String, senderName: String?, closedGroup: AccountId, groupName: String) {
        val updateData = UpdateMessageData(UpdateMessageData.Kind.GroupInvitation(
            groupAccountId = closedGroup.hexString,
            invitingAdminId = senderPublicKey,
            invitingAdminName = senderName,
            groupName = groupName
        ))
        insertUpdateControlMessage(updateData, sentTimestamp, senderPublicKey, closedGroup)
    }

    private fun insertUpdateControlMessage(updateData: UpdateMessageData, sentTimestamp: Long, senderPublicKey: String?, closedGroup: AccountId): MessageId? {
        val userPublicKey = getUserPublicKey()!!
        val address = fromSerialized(closedGroup.hexString)
        val recipient = recipientRepository.getRecipientSync(address)
        val threadDb = threadDatabase
        val threadID = threadDb.getThreadIdIfExistsFor(address)
        val expiryMode = recipient.expiryMode
        val expiresInMillis = expiryMode?.expiryMillis ?: 0
        val expireStartedAt = if (expiryMode is ExpiryMode.AfterSend) sentTimestamp else 0
        val inviteJson = updateData.toJSON()


        if (senderPublicKey == null || senderPublicKey == userPublicKey) {
            val infoMessage = OutgoingGroupMediaMessage(
                address,
                inviteJson,
                closedGroup.hexString,
                null,
                sentTimestamp,
                expiresInMillis,
                expireStartedAt,
                true,
                null,
                listOf(),
                listOf(),
                null
            )
            val mmsDB = mmsDatabase
            val mmsSmsDB = mmsSmsDatabase
            // check for conflict here, not returning duplicate in case it's different
            if (mmsSmsDB.getMessageFor(sentTimestamp, userPublicKey) != null) return null
            val infoMessageID = mmsDB.insertMessageOutbox(
                infoMessage,
                threadID,
                false,
                runThreadUpdate = true
            )
            mmsDB.markAsSent(infoMessageID, true)
            return MessageId(infoMessageID, mms = true)
        } else {
            val m = IncomingTextMessage(fromSerialized(senderPublicKey), 1, sentTimestamp, "", Optional.of(Address.Group(closedGroup)), expiresInMillis, expireStartedAt, true, false)
            val infoMessage = IncomingGroupMessage(m, inviteJson, true)
            val smsDB = smsDatabase
            val insertResult = smsDB.insertMessageInbox(infoMessage,  true)
            return insertResult.orNull()?.messageId?.let { MessageId(it, mms = false) }
        }
    }

    override fun setServerCapabilities(server: String, capabilities: List<String>) {
        return lokiAPIDatabase.setServerCapabilities(server, capabilities)
    }

    override fun getServerCapabilities(server: String): List<String>? {
        return lokiAPIDatabase.getServerCapabilities(server)
    }

    override fun getAllOpenGroups(): Map<Long, OpenGroup> {
        return lokiThreadDatabase.getAllOpenGroups()
    }

    override fun getAllGroups(includeInactive: Boolean): List<GroupRecord> {
        return groupDatabase.getAllGroups(includeInactive)
    }

    override suspend fun addOpenGroup(urlAsString: String) {
        return openGroupManager.get().addOpenGroup(urlAsString)
    }

    override fun getOrCreateThreadIdFor(address: Address): Long {
        return threadDatabase.getOrCreateThreadIdFor(address)
    }

    override fun getThreadId(address: Address): Long? {
        val threadID = threadDatabase.getThreadIdIfExistsFor(address)
        return if (threadID < 0) null else threadID
    }

    override fun getThreadIdForMms(mmsId: Long): Long {
        val mmsDb = mmsDatabase
        val cursor = mmsDb.getMessage(mmsId)
        val reader = mmsDb.readerFor(cursor)
        val threadId = reader.next?.threadId
        cursor.close()
        return threadId ?: -1
    }

    override fun getRecipientForThread(threadId: Long): Recipient? {
        return threadDatabase.getRecipientForThreadId(threadId)
            ?.let(recipientRepository::getRecipientSync)
    }
    override fun setAutoDownloadAttachments(
        recipient: Address,
        shouldAutoDownloadAttachments: Boolean
    ) {
        recipientDatabase.save(recipient) {
            it.copy(autoDownloadAttachments = shouldAutoDownloadAttachments)
        }
    }

    override fun getLastUpdated(threadID: Long): Long {
        val threadDB = threadDatabase
        return threadDB.getLastUpdated(threadID)
    }

    override fun trimThreadBefore(threadID: Long, timestamp: Long) {
        val threadDB = threadDatabase
        threadDB.trimThreadBefore(threadID, timestamp)
    }

    override fun getMessageCount(threadID: Long): Long {
        val mmsSmsDb = mmsSmsDatabase
        return mmsSmsDb.getConversationCount(threadID)
    }

    override fun getTotalPinned(): Int {
        return configFactory.withUserConfigs {
            var totalPins = 0

            // check if the note to self is pinned
            if (it.userProfile.getNtsPriority() == PRIORITY_PINNED) {
                totalPins ++
            }

            // check for 1on1
            it.contacts.all().forEach { contact ->
                if (contact.priority == PRIORITY_PINNED) {
                    totalPins ++
                }
            }

            // check groups and communities
            it.userGroups.all().forEach { group ->
                when(group){
                    is GroupInfo.ClosedGroupInfo -> {
                        if (group.priority == PRIORITY_PINNED) {
                            totalPins ++
                        }
                    }
                    is GroupInfo.CommunityGroupInfo -> {
                        if (group.priority == PRIORITY_PINNED) {
                            totalPins ++
                        }
                    }

                    is GroupInfo.LegacyGroupInfo -> {
                        if (group.priority == PRIORITY_PINNED) {
                            totalPins ++
                        }
                    }
                }
            }

            totalPins
        }
    }

    override fun setPinned(address: Address, isPinned: Boolean) {
        val isLocalNumber = address.address == getUserPublicKey()
        configFactory.withMutableUserConfigs { configs ->
            val pinPriority = if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE
            when (address) {
                is Address.Standard -> {
                    if (isLocalNumber) {
                        configs.userProfile.setNtsPriority(pinPriority)
                    } else {
                        configs.contacts.upsertContact(address) {
                            priority = pinPriority
                        }
                    }
                }

                is Address.LegacyGroup -> {
                    configs.userGroups.getOrConstructLegacyGroupInfo(address.groupPublicKeyHex)
                        .copy(priority = pinPriority)
                        .let(configs.userGroups::set)
                }

                is Address.Group -> {
                    val newGroupInfo = configs.userGroups
                        .getOrConstructClosedGroup(address.accountId.hexString)
                        .copy(priority = pinPriority)
                    configs.userGroups.set(newGroupInfo)
                }

                is Address.Community -> {
                    val openGroup = getOpenGroup(address) ?: return@withMutableUserConfigs
                    val newGroupInfo = configs.userGroups.getOrConstructCommunityInfo(
                        baseUrl = openGroup.server,
                        room = openGroup.room,
                        pubKeyHex = openGroup.publicKey,
                    ).copy(priority = pinPriority)
                    configs.userGroups.set(newGroupInfo)
                }

                else -> {}
            }

        }
    }

    override fun isRead(threadId: Long) : Boolean {
        val threadDB = threadDatabase
        return threadDB.isRead(threadId)
    }

    override fun setThreadCreationDate(threadId: Long, newDate: Long) {
        val threadDb = threadDatabase
        threadDb.setCreationDate(threadId, newDate)
    }

    override fun getLastLegacyRecipient(threadRecipient: String): String? =
        lokiAPIDatabase.getLastLegacySenderAddress(threadRecipient)

    override fun setLastLegacyRecipient(threadRecipient: String, senderRecipient: String?) {
        lokiAPIDatabase.setLastLegacySenderAddress(threadRecipient, senderRecipient)
    }
    override fun clearMessages(threadID: Long, fromUser: Address?): Boolean {
        val threadDb = threadDatabase
        if (fromUser == null) {
            // this deletes all *from* thread, not deleting the actual thread
            smsDatabase.deleteThread(threadID)
            mmsDatabase.deleteThread(threadID, updateThread = true) // threadDB update called from within
        } else {
            // this deletes all *from* thread, not deleting the actual thread
            smsDatabase.deleteMessagesFrom(threadID, fromUser.toString())
            mmsDatabase.deleteMessagesFrom(threadID, fromUser.toString())
        }

        threadDb.setRead(threadID, true)

        return true
    }

    override fun clearMedia(threadID: Long, fromUser: Address?): Boolean {
        mmsDatabase.deleteMediaFor(threadID, fromUser?.toString())
        return true
    }

    override fun getAttachmentDataUri(attachmentId: AttachmentId): Uri {
        return PartAuthority.getAttachmentDataUri(attachmentId)
    }

    override fun getAttachmentThumbnailUri(attachmentId: AttachmentId): Uri {
        return PartAuthority.getAttachmentThumbnailUri(attachmentId)
    }

    override fun insertDataExtractionNotificationMessage(senderPublicKey: String, message: DataExtractionNotificationInfoMessage, sentTimestamp: Long) {
        val address = fromSerialized(senderPublicKey)
        val recipient = recipientRepository.getRecipientSync(address)

        if (recipient.blocked) return
        val threadId = getThreadId(address) ?: return
        val expiresInMillis = recipient.expiryMode.expiryMillis
        val expireStartedAt = if (recipient.expiryMode is ExpiryMode.AfterSend) sentTimestamp else 0
        val mediaMessage = IncomingMediaMessage(
            address,
            sentTimestamp,
            -1,
            expiresInMillis,
            expireStartedAt,
            false,
            false,
            Optional.absent(),
            Optional.absent(),
            Optional.absent(),
            null,
            Optional.absent(),
            Optional.absent(),
            Optional.absent(),
            Optional.of(message)
        )

        mmsDatabase.insertSecureDecryptedMessageInbox(mediaMessage, threadId, runThreadUpdate = true)
    }

    /**
     * This will create a control message used to indicate that you have accepted a message request
     */
    override fun insertMessageRequestResponseFromYou(threadId: Long){
        val userPublicKey = getUserPublicKey() ?: return

        val message = IncomingMediaMessage(
            fromSerialized(userPublicKey),
            clock.currentTimeMills(),
            -1,
            0,
            0,
            true,
            false,
            Optional.absent(),
            Optional.absent(),
            Optional.absent(),
            null,
            Optional.absent(),
            Optional.absent(),
            Optional.absent(),
            Optional.absent()
        )
        mmsDatabase.insertSecureDecryptedMessageInbox(message, threadId, runThreadUpdate = false)
    }

    override fun insertCallMessage(senderPublicKey: String, callMessageType: CallMessageType, sentTimestamp: Long) {
        val address = fromSerialized(senderPublicKey)
        val recipient = recipientRepository.getRecipientSync(address)
        val expiryMode = recipient.expiryMode.coerceSendToRead()
        val expiresInMillis = expiryMode.expiryMillis
        val expireStartedAt = if (expiryMode != ExpiryMode.NONE) clock.currentTimeMills() else 0
        val callMessage = IncomingTextMessage.fromCallInfo(callMessageType, address, Optional.absent(), sentTimestamp, expiresInMillis, expireStartedAt)
        smsDatabase.insertCallMessage(callMessage)
    }

    override fun conversationHasOutgoing(userPublicKey: String): Boolean {
        val database = threadDatabase
        val threadId = database.getThreadIdIfExistsFor(userPublicKey)

        if (threadId == -1L) return false

        return database.getLastSeenAndHasSent(threadId).second() ?: false
    }

    override fun getLastInboxMessageId(server: String): Long? {
        return lokiAPIDatabase.getLastInboxMessageId(server)
    }

    override fun setLastInboxMessageId(server: String, messageId: Long) {
        lokiAPIDatabase.setLastInboxMessageId(server, messageId)
    }

    override fun getLastOutboxMessageId(server: String): Long? {
        return lokiAPIDatabase.getLastOutboxMessageId(server)
    }

    override fun setLastOutboxMessageId(server: String, messageId: Long) {
        lokiAPIDatabase.setLastOutboxMessageId(server, messageId)
    }

    override fun addReaction(
        threadId: Long,
        reaction: Reaction,
        messageSender: String,
        notifyUnread: Boolean
    ) {
        val timestamp = reaction.timestamp

        val messageId = if (timestamp != null && timestamp > 0) {
            val messageRecord = mmsSmsDatabase.getMessageForTimestamp(threadId, timestamp) ?: return
            if (messageRecord.isDeleted) return
            MessageId(messageRecord.id, messageRecord.isMms)
        } else {
            Log.d(TAG, "Invalid reaction timestamp: $timestamp. Not adding")
            return
        }

        addReaction(messageId, reaction, messageSender)
    }

    override fun addReaction(messageId: MessageId, reaction: Reaction, messageSender: String) {
        reactionDatabase.addReaction(
            ReactionRecord(
                messageId = messageId,
                author = messageSender,
                emoji = reaction.emoji!!,
                serverId = reaction.serverId!!,
                count = reaction.count!!,
                sortId = reaction.index!!,
                dateSent = reaction.dateSent!!,
                dateReceived = reaction.dateReceived!!
            )
        )
    }

    override fun addReactions(
        reactions: Map<MessageId, List<ReactionRecord>>,
        replaceAll: Boolean,
        notifyUnread: Boolean
    ) {
        reactionDatabase.addReactions(
            reactionsByMessageId = reactions,
            replaceAll = replaceAll
        )
    }

    override fun removeReaction(
        emoji: String,
        messageTimestamp: Long,
        threadId: Long,
        author: String,
        notifyUnread: Boolean
    ) {
        val messageRecord = mmsSmsDatabase.getMessageForTimestamp(threadId, messageTimestamp) ?: return
        reactionDatabase.deleteReaction(
            emoji,
            MessageId(messageRecord.id, messageRecord.isMms),
            author
        )
    }

    override fun updateReactionIfNeeded(message: Message, sender: String, openGroupSentTimestamp: Long) {
        val database = reactionDatabase
        var reaction = database.getReactionFor(message.sentTimestamp!!, sender) ?: return
        if (openGroupSentTimestamp != -1L) {
            addReceivedMessageTimestamp(openGroupSentTimestamp)
            reaction = reaction.copy(dateSent = openGroupSentTimestamp)
        }
        message.serverHash?.let {
            reaction = reaction.copy(serverId = it)
        }
        message.openGroupServerMessageID?.let {
            reaction = reaction.copy(serverId = "$it")
        }
        database.updateReaction(reaction)
    }

    override fun deleteReactions(messageId: MessageId) {
        reactionDatabase.deleteMessageReactions(messageId)
    }

    override fun deleteReactions(messageIds: List<Long>, mms: Boolean) {
        reactionDatabase.deleteMessageReactions(
            messageIds.map { MessageId(it, mms) }
        )
    }

    override fun setBlocked(recipients: Iterable<Address>, isBlocked: Boolean) {
        configFactory.withMutableUserConfigs { configs ->
            recipients.filterIsInstance<Address.Standard>()
                .forEach { standard ->
                    configs.contacts.upsertContact(standard) {
                        this.blocked = isBlocked
                        Log.d(TAG, "Setting contact ${standard.debugString} blocked state to $isBlocked")
                    }
                }
        }
    }

    override fun setExpirationConfiguration(address: Address, expiryMode: ExpiryMode) {
        if (expiryMode == ExpiryMode.NONE) {
            // Clear the legacy recipients on updating config to be none
            lokiAPIDatabase.setLastLegacySenderAddress(address.toString(), null)
        }

        when (address) {
            is Address.LegacyGroup -> {
                configFactory.withMutableUserConfigs {
                    val groupInfo = it.userGroups.getLegacyGroupInfo(address.groupPublicKeyHex)
                        ?.copy(disappearingTimer = expiryMode.expirySeconds) ?: return@withMutableUserConfigs
                    it.userGroups.set(groupInfo)
                }
            }

            is Address.Group -> {
                configFactory.withMutableGroupConfigs(address.accountId) { configs ->
                    configs.groupInfo.setExpiryTimer(expiryMode.expirySeconds)
                }
            }

            is Address.Standard -> {
                if (address.address == getUserPublicKey()) {
                    configFactory.withMutableUserConfigs {
                        it.userProfile.setNtsExpiry(expiryMode)
                    }
                } else {
                    configFactory.withMutableUserConfigs {
                        val contact = it.contacts.get(address.toString())?.copy(expiryMode = expiryMode) ?: return@withMutableUserConfigs
                        it.contacts.set(contact)
                    }
                }
            }

            else -> {
                Log.w(TAG, "setExpirationConfiguration called with unsupported address: ${address.debugString}")
            }
        }
    }
}

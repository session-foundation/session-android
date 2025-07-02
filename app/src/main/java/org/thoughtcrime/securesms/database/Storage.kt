package org.thoughtcrime.securesms.database

import android.content.Context
import android.net.Uri
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_PINNED
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.KeyPair
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.BlindedIdMapping
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.GroupAvatarDownloadJob
import org.session.libsession.messaging.jobs.Job
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveJob
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.ProfileUpdateHandler
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.control.MessageRequestResponse
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
import org.session.libsession.messaging.open_groups.GroupMember
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
import org.session.libsession.utilities.GroupDisplayInfo
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.recipients.RecipientSettings
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.upsertContact
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.KeyHelper
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.crypto.KeyPairUtilities
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.DatabaseComponent.Companion.get
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.util.FilenameUtils
import org.thoughtcrime.securesms.util.SessionMetaProtocol
import org.thoughtcrime.securesms.util.SessionMetaProtocol.clearReceivedMessages
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.collections.set
import network.loki.messenger.libsession_util.util.Contact as LibSessionContact
import network.loki.messenger.libsession_util.util.GroupMember as LibSessionGroupMember

private const val TAG = "Storage"

@Singleton
open class Storage @Inject constructor(
    @ApplicationContext context: Context,
    helper: Provider<SQLCipherOpenHelper>,
    private val configFactory: ConfigFactory,
    private val jobDatabase: SessionJobDatabase,
    private val threadDatabase: ThreadDatabase,
    private val recipientDatabase: RecipientDatabase,
    private val attachmentDatabase: AttachmentDatabase,
    private val lokiAPIDatabase: LokiAPIDatabase,
    private val groupDatabase: GroupDatabase,
    private val lokiMessageDatabase: LokiMessageDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val blindedIdMappingDatabase: BlindedIdMappingDatabase,
    private val groupMemberDatabase: GroupMemberDatabase,
    private val reactionDatabase: ReactionDatabase,
    private val lokiThreadDatabase: LokiThreadDatabase,
    private val notificationManager: MessageNotifier,
    private val messageDataProvider: MessageDataProvider,
    private val messageExpirationManager: SSKEnvironment.MessageExpirationManagerProtocol,
    private val clock: SnodeClock,
    private val preferences: TextSecurePreferences,
    private val openGroupManager: Lazy<OpenGroupManager>,
    private val recipientRepository: RecipientRepository,
    private val profileUpdateHandler: ProfileUpdateHandler,
) : Database(context, helper), StorageProtocol, ThreadDatabase.ConversationThreadUpdateListener {

    init {
        threadDatabase.setUpdateListener(this)
    }

    override fun threadCreated(address: Address, threadId: Long) {
        val localUserAddress = getUserPublicKey() ?: return
        val approved = recipientRepository.getRecipientSyncOrEmpty(address).approved
        if (!approved && localUserAddress != address.toString()) return // don't store unapproved / message requests

        when {
            address.isLegacyGroup -> {
                val accountId = GroupUtil.doubleDecodeGroupId(address.toString())
                val closedGroup = getGroup(address.toGroupString())
                if (closedGroup != null && closedGroup.isActive) {
                    configFactory.withMutableUserConfigs { configs ->
                        val legacyGroup = configs.userGroups.getOrConstructLegacyGroupInfo(accountId)
                        configs.userGroups.set(legacyGroup)
                        val newVolatileParams = configs.convoInfoVolatile.getOrConstructLegacyGroup(accountId).copy(
                            lastRead = clock.currentTimeMills(),
                        )
                        configs.convoInfoVolatile.set(newVolatileParams)
                    }

                }
            }
            address.isGroupV2 -> {
                configFactory.withMutableUserConfigs { configs ->
                    val accountId = address.toString()
                    configs.userGroups.getClosedGroup(accountId)
                        ?: return@withMutableUserConfigs Log.d("Closed group doesn't exist locally", NullPointerException())

                    configs.convoInfoVolatile.getOrConstructClosedGroup(accountId)
                }

            }
            address.isCommunity -> {
                // these should be added on the group join / group info fetch
                Log.w("Loki", "Thread created called for open group address, not adding any extra information")
            }

            address.isContact -> {
                // non-standard contact prefixes: 15, 00 etc shouldn't be stored in config
                if (IdPrefix.fromValue(address.toString()) != IdPrefix.STANDARD) return
                // don't update our own address into the contacts DB
                if (getUserPublicKey() != address.toString()) {
                    configFactory.withMutableUserConfigs { configs ->
                        configs.contacts.upsertContact(address.toString()) {
                            priority = PRIORITY_VISIBLE
                        }
                    }
                } else {
                    configFactory.withMutableUserConfigs { configs ->
                        configs.userProfile.setNtsPriority(PRIORITY_VISIBLE)
                    }

                    threadDatabase.setHasSent(threadId, true)
                }

                configFactory.withMutableUserConfigs { configs ->
                    configs.convoInfoVolatile.getOrConstructOneToOne(address.toString())
                }
            }
        }
    }

    override fun getUserPublicKey(): String? { return preferences.getLocalNumber() }

    override fun getUserX25519KeyPair(): ECKeyPair { return lokiAPIDatabase.getUserX25519KeyPair() }

    override fun getUserED25519KeyPair(): KeyPair? { return KeyPairUtilities.getUserED25519KeyPair(context) }

    override fun getUserBlindedAccountId(serverPublicKey: String): AccountId? {
        val userKeyPair = getUserED25519KeyPair() ?: return null
        return AccountId(
            IdPrefix.BLINDED,
            BlindKeyAPI.blind15KeyPairOrNull(
                ed25519SecretKey = userKeyPair.secretKey.data,
                serverPubKey = Hex.fromStringCondensed(serverPublicKey),
            )!!.pubKey.data
        )
    }

    override fun getUserProfile(): Profile {
        return configFactory.withUserConfigs { configs ->
            val pic = configs.userProfile.getPic()
            Profile(
                displayName = configs.userProfile.getName(),
                profilePictureURL = pic.url.takeIf { it.isNotBlank() },
                profileKey = pic.key.data.takeIf { pic.url.isNotBlank() },
            )
        }
    }

    override fun setBlocksCommunityMessageRequests(recipient: Address, blocksMessageRequests: Boolean) {
        val db = recipientDatabase
        db.setBlocksCommunityMessageRequests(recipient, blocksMessageRequests)
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
            messageDataProvider.deleteMessages(mmsMessages.map(MessageRecord::id), threadId, isSms = false)
        }
        if (smsMessages.isNotEmpty()) {
            messageDataProvider.deleteMessages(smsMessages.map(MessageRecord::id), threadId, isSms = true)
        }
    }

    override fun clearAllMessages(threadId: Long): List<String?> {
        val messages = mmsSmsDatabase.getAllMessagesWithHash(threadId)
        val (mmsMessages, smsMessages) = messages.partition { it.first.isMms }
        if (mmsMessages.isNotEmpty()) {
            messageDataProvider.deleteMessages(mmsMessages.map{ it.first.id }, threadId, isSms = false)
        }
        if (smsMessages.isNotEmpty()) {
            messageDataProvider.deleteMessages(smsMessages.map{ it.first.id }, threadId, isSms = true)
        }

        return messages.map { it.second } // return the message hashes
    }

    override fun markConversationAsRead(threadId: Long, lastSeenTime: Long, force: Boolean) {
        val threadDb = threadDatabase
        getRecipientForThread(threadId)?.let { recipient ->
            val currentLastRead = threadDb.getLastSeenAndHasSent(threadId).first()
            // don't set the last read in the volatile if we didn't set it in the DB
            if (!threadDb.markAllAsRead(threadId, recipient.isGroupOrCommunity, lastSeenTime, force) && !force) return

            // don't process configs for inbox recipients
            if (recipient.isCommunityInbox) return

            configFactory.withMutableUserConfigs { configs ->
                val config = configs.convoInfoVolatile
                val convo = when {
                    // recipient closed group
                    recipient.isLegacyGroup -> config.getOrConstructLegacyGroup(GroupUtil.doubleDecodeGroupId(recipient.toString()))
                    recipient.isGroupV2 -> config.getOrConstructClosedGroup(recipient.toString())
                    // recipient is open group
                    recipient.isCommunity -> {
                        val openGroupJoinUrl = getOpenGroup(threadId)?.joinURL ?: return@withMutableUserConfigs
                        BaseCommunityInfo.parseFullUrl(openGroupJoinUrl)?.let { (base, room, pubKey) ->
                            config.getOrConstructCommunity(base, room, pubKey)
                        } ?: return@withMutableUserConfigs
                    }
                    // otherwise recipient is one to one
                    recipient.isContact -> {
                        // don't process non-standard account IDs though
                        if (IdPrefix.fromValue(recipient.toString()) != IdPrefix.STANDARD) return@withMutableUserConfigs
                        config.getOrConstructOneToOne(recipient.toString())
                    }
                    else -> throw NullPointerException("Weren't expecting to have a convo with address ${recipient.toString()}")
                }
                convo.lastRead = lastSeenTime
                if (convo.unread) {
                    convo.unread = lastSeenTime <= currentLastRead
                    notifyConversationListListeners()
                }
                config.set(convo)
            }
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
        groupPublicKey: String?,
        openGroupID: String?,
        attachments: List<Attachment>,
        runThreadUpdate: Boolean): MessageId? {
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
        val group: Optional<SignalServiceGroup> = when {
            openGroupID != null -> Optional.of(SignalServiceGroup(openGroupID.toByteArray(), SignalServiceGroup.GroupType.PUBLIC_CHAT))
            groupPublicKey != null && groupPublicKey.startsWith(IdPrefix.GROUP.value) -> {
                Optional.of(SignalServiceGroup(Hex.fromStringCondensed(groupPublicKey), SignalServiceGroup.GroupType.SIGNAL))
            }
            groupPublicKey != null -> {
                val doubleEncoded = GroupUtil.doubleEncodeGroupID(groupPublicKey)
                Optional.of(SignalServiceGroup(GroupUtil.getDecodedGroupIDAsData(doubleEncoded), SignalServiceGroup.GroupType.SIGNAL))
            }
            else -> Optional.absent()
        }

        val targetAddress = if ((isUserSender || isUserBlindedSender) && !message.syncTarget.isNullOrEmpty()) {
            fromSerialized(message.syncTarget!!)
        } else if (group.isPresent) {
            val idHex = group.get().groupId.toHexString()
            if (idHex.startsWith(IdPrefix.GROUP.value)) {
                fromSerialized(idHex)
            } else {
                fromSerialized(GroupUtil.getEncodedId(group.get()))
            }
        } else if (message.recipient?.startsWith(IdPrefix.GROUP.value) == true) {
            fromSerialized(message.recipient!!)
        } else {
            senderAddress
        }
        if (!targetAddress.isGroupOrCommunity && IdPrefix.fromValue(targetAddress.address) == IdPrefix.STANDARD) {
            configFactory.withMutableUserConfigs { configs ->
                configs.contacts.upsertContact(targetAddress.address) {
                    if (isUserSender || isUserBlindedSender) {
                        approved = true
                    } else {
                        approvedMe = true
                    }
                }
            }
        }
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
                val mediaMessage = IncomingMediaMessage.from(message, senderAddress, expiresInMillis, expireStartedAt, group, signalServiceAttachments, quote, linkPreviews)
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
                else IncomingTextMessage.from(message, senderAddress, group, expiresInMillis, expireStartedAt)
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

    override fun getMessageReceiveJob(messageReceiveJobID: String): MessageReceiveJob? {
        return jobDatabase.getMessageReceiveJob(messageReceiveJobID)
    }

    override fun getGroupAvatarDownloadJob(server: String, room: String, imageId: String?): GroupAvatarDownloadJob? {
        return jobDatabase.getGroupAvatarDownloadJob(server, room, imageId)
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

    override fun conversationInConfig(publicKey: String?, groupPublicKey: String?, openGroupId: String?, visibleOnly: Boolean): Boolean {
        return configFactory.conversationInConfig(publicKey, groupPublicKey, openGroupId, visibleOnly)
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
        return lokiAPIDatabase.getOpenGroupPublicKey(server)
    }

    override fun setOpenGroupPublicKey(server: String, newValue: String) {
        lokiAPIDatabase.setOpenGroupPublicKey(server, newValue)
    }

    override fun getLastMessageServerID(room: String, server: String): Long? {
        return lokiAPIDatabase.getLastMessageServerID(room, server)
    }

    override fun setLastMessageServerID(room: String, server: String, newValue: Long) {
        lokiAPIDatabase.setLastMessageServerID(room, server, newValue)
    }

    override fun removeLastMessageServerID(room: String, server: String) {
        lokiAPIDatabase.removeLastMessageServerID(room, server)
    }

    override fun getLastDeletionServerID(room: String, server: String): Long? {
        return lokiAPIDatabase.getLastDeletionServerID(room, server)
    }

    override fun setLastDeletionServerID(room: String, server: String, newValue: Long) {
        lokiAPIDatabase.setLastDeletionServerID(room, server, newValue)
    }

    override fun removeLastDeletionServerID(room: String, server: String) {
        lokiAPIDatabase.removeLastDeletionServerID(room, server)
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

    override fun setGroupMemberRoles(members: List<GroupMember>) {
        groupMemberDatabase.setGroupMembers(members)
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
        openGroupSentTimestamp: Long,
        threadId: Long
    ) {
        if (messageId.mms) {
            val mmsDb = mmsDatabase
            mmsDb.updateSentTimestamp(messageId.id, openGroupSentTimestamp, threadId)
        } else {
            val smsDb = smsDatabase
            smsDb.updateSentTimestamp(messageId.id, openGroupSentTimestamp, threadId)
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

    override fun createGroup(groupId: String, title: String?, members: List<Address>, avatar: SignalServiceAttachmentPointer?, relay: String?, admins: List<Address>, formationTimestamp: Long) {
        groupDatabase.create(groupId, title, members, avatar, relay, admins, formationTimestamp)
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

    override fun insertIncomingInfoMessage(context: Context, senderPublicKey: String, groupID: String, type: SignalServiceGroup.Type, name: String, members: Collection<String>, admins: Collection<String>, sentTimestamp: Long): Long? {
        val group = SignalServiceGroup(type, GroupUtil.getDecodedGroupIDAsData(groupID), SignalServiceGroup.GroupType.SIGNAL, name, members.toList(), null, admins.toList())
        val m = IncomingTextMessage(fromSerialized(senderPublicKey), 1, sentTimestamp, "", Optional.of(group), 0, 0, true, false)
        val updateData = UpdateMessageData.buildGroupUpdate(type, name, members)?.toJSON()
        val infoMessage = IncomingGroupMessage(m, updateData, true)
        val smsDB = smsDatabase
        return smsDB.insertMessageInbox(infoMessage,  true).orNull().messageId
    }

    override fun updateInfoMessage(context: Context, messageId: Long, groupID: String, type: SignalServiceGroup.Type, name: String, members: Collection<String>) {
        val mmsDB = mmsDatabase
        val updateData = UpdateMessageData.buildGroupUpdate(type, name, members)?.toJSON()
        mmsDB.updateInfoMessage(messageId, updateData)
    }

    override fun insertOutgoingInfoMessage(context: Context, groupID: String, type: SignalServiceGroup.Type, name: String, members: Collection<String>, admins: Collection<String>, threadID: Long, sentTimestamp: Long): Long? {
        val userPublicKey = getUserPublicKey()!!
        val recipient = fromSerialized(groupID)
        val updateData = UpdateMessageData.buildGroupUpdate(type, name, members)?.toJSON() ?: ""
        val infoMessage = OutgoingGroupMediaMessage(recipient, updateData, groupID, null, sentTimestamp, 0, 0, true, null, listOf(), listOf())
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

    override fun updateGroupInfoChange(messageId: Long, newType: UpdateMessageData.Kind) {
        val mmsDB = mmsDatabase
        val newMessage = UpdateMessageData.buildGroupLeaveUpdate(newType)
        mmsDB.updateInfoMessage(messageId, newMessage.toJSON())
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
        val expiryMode = recipient?.expiryMode
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
                listOf()
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
            val group = SignalServiceGroup(Hex.fromStringCondensed(closedGroup.hexString), SignalServiceGroup.GroupType.SIGNAL)
            val m = IncomingTextMessage(fromSerialized(senderPublicKey), 1, sentTimestamp, "", Optional.of(group), expiresInMillis, expireStartedAt, true, false)
            val infoMessage = IncomingGroupMessage(m, inviteJson, true)
            val smsDB = smsDatabase
            val insertResult = smsDB.insertMessageInbox(infoMessage,  true)
            return insertResult.orNull()?.messageId?.let { MessageId(it, mms = false) }
        }
    }

    override fun setServerCapabilities(server: String, capabilities: List<String>) {
        return lokiAPIDatabase.setServerCapabilities(server, capabilities)
    }

    override fun getServerCapabilities(server: String): List<String> {
        return lokiAPIDatabase.getServerCapabilities(server)
    }

    override fun getAllOpenGroups(): Map<Long, OpenGroup> {
        return lokiThreadDatabase.getAllOpenGroups()
    }

    override fun updateOpenGroup(openGroup: OpenGroup) {
        openGroupManager.get().updateOpenGroup(openGroup, context)

        groupDatabase.updateTitle(
            groupID = GroupUtil.getEncodedOpenGroupID(openGroup.groupId.toByteArray()),
            newValue = openGroup.name
        )
    }

    override fun getAllGroups(includeInactive: Boolean): List<GroupRecord> {
        return groupDatabase.getAllGroups(includeInactive)
    }

    override suspend fun addOpenGroup(urlAsString: String) {
        return openGroupManager.get().addOpenGroup(urlAsString, context)
    }

    override fun onOpenGroupAdded(server: String, room: String) {
        configFactory.withMutableUserConfigs { configs ->
            val groups = configs.userGroups
            val volatileConfig = configs.convoInfoVolatile
            val openGroup = getOpenGroup(room, server) ?: return@withMutableUserConfigs
            val (infoServer, infoRoom, pubKey) = BaseCommunityInfo.parseFullUrl(openGroup.joinURL) ?: return@withMutableUserConfigs
            val pubKeyHex = Hex.toStringCondensed(pubKey)
            val communityInfo = groups.getOrConstructCommunityInfo(infoServer, infoRoom, pubKeyHex)
            groups.set(communityInfo)
            val volatile = volatileConfig.getOrConstructCommunity(infoServer, infoRoom, pubKey)
            if (volatile.lastRead != 0L) {
                val threadId = getThreadId(openGroup) ?: return@withMutableUserConfigs
                markConversationAsRead(threadId, volatile.lastRead, force = true)
            }
            volatileConfig.set(volatile)
        }
    }

    override fun hasBackgroundGroupAddJob(groupJoinUrl: String): Boolean {
        return jobDatabase.hasBackgroundGroupAddJob(groupJoinUrl)
    }

    override fun getOrCreateThreadIdFor(address: Address): Long {
        return threadDatabase.getOrCreateThreadIdFor(address)
    }

    override fun getThreadIdFor(publicKey: String, groupPublicKey: String?, openGroupID: String?, createThread: Boolean): Long? {
        val database = threadDatabase
        return if (!openGroupID.isNullOrEmpty()) {
            val recipient = fromSerialized(GroupUtil.getEncodedOpenGroupID(openGroupID.toByteArray()))
            database.getThreadIdIfExistsFor(recipient).let { if (it == -1L) null else it }
        } else if (!groupPublicKey.isNullOrEmpty() && !groupPublicKey.startsWith(IdPrefix.GROUP.value)) {
            val recipient = fromSerialized(GroupUtil.doubleEncodeGroupID(groupPublicKey))
            if (createThread) database.getOrCreateThreadIdFor(recipient)
            else database.getThreadIdIfExistsFor(recipient).let { if (it == -1L) null else it }
        } else if (!groupPublicKey.isNullOrEmpty()) {
            val recipient = fromSerialized(groupPublicKey)
            if (createThread) database.getOrCreateThreadIdFor(recipient)
            else database.getThreadIdIfExistsFor(recipient).let { if (it == -1L) null else it }
        } else {
            val recipient = fromSerialized(publicKey)
            if (createThread) database.getOrCreateThreadIdFor(recipient)
            else database.getThreadIdIfExistsFor(recipient).let { if (it == -1L) null else it }
        }
    }

    override fun getThreadId(publicKeyOrOpenGroupID: String): Long? {
        val address = fromSerialized(publicKeyOrOpenGroupID)
        return getThreadId(address)
    }

    override fun getThreadId(openGroup: OpenGroup): Long? {
        return GroupManager.getOpenGroupThreadID("${openGroup.server.removeSuffix("/")}.${openGroup.room}", context)
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

    override fun deleteContactAndSyncConfig(accountId: String) {
        deleteContact(accountId)
        // also handle the contact removal from the config's point of view
        configFactory.removeContact(accountId)
    }

    private fun deleteContact(accountId: String){
        recipientDatabase.deleteRecipient(accountId)

        val threadId: Long = threadDatabase.getThreadIdIfExistsFor(accountId)
        deleteConversation(threadId)

        notifyRecipientListeners()
    }

    override fun getRecipientForThread(threadId: Long): Address? {
        return threadDatabase.getRecipientForThreadId(threadId)
    }

    override fun getRecipientSettings(address: Address): RecipientSettings? {
        return recipientDatabase.getRecipientSettings(address)
    }

    override fun syncLibSessionContacts(contacts: List<LibSessionContact>, timestamp: Long?) {
        val mappingDb = blindedIdMappingDatabase
        val moreContacts = contacts.filter { contact ->
            val id = AccountId(contact.id)
            id.prefix?.isBlinded() == false || mappingDb.getBlindedIdMapping(contact.id).none { it.accountId != null }
        }
        moreContacts.forEach { contact ->
            val address = fromSerialized(contact.id)

            if (contact.priority == PRIORITY_HIDDEN) {
                getThreadId(address)?.let(::deleteConversation)
            } else {
                (
                    getThreadId(address) ?: getOrCreateThreadIdFor(address).also {
                        setThreadCreationDate(it, 0)
                    }
                ).also { setPinned(it, contact.priority == PRIORITY_PINNED) }
            }
        }

        // if we have contacts locally but that are missing from the config, remove their corresponding thread
        val currentUserKey = getUserPublicKey()

        //NOTE: We used to cycle through all Contact here instead or all Recipients, but turns out a Contact isn't saved until we have a name, nickname or avatar
        // which in the case of contacts we are messaging for the first time and who haven't yet approved us, it won't be the case
        // But that person is saved in the Recipient db. We might need to investigate how to clean the relationship between Recipients, Contacts and config Contacts.
        val removedContacts = recipientDatabase.allRecipients.filter { localContact ->
            IdPrefix.fromValue(localContact.toString()) == IdPrefix.STANDARD && // only want standard address
            localContact.isContact && // only for conversations
            localContact.address != currentUserKey && // we don't want to remove ourselves (ie, our Note to Self)
            moreContacts.none { it.id == localContact.address } // we don't want to remove contacts that are present in the config
        }
        removedContacts.forEach {
            deleteContact(it.address)
        }
    }

    override fun setAutoDownloadAttachments(
        recipient: Address,
        shouldAutoDownloadAttachments: Boolean
    ) {
        val recipientDb = recipientDatabase
        recipientDb.setAutoDownloadAttachments(recipient, shouldAutoDownloadAttachments)
    }

    override fun getLastUpdated(threadID: Long): Long {
        val threadDB = threadDatabase
        return threadDB.getLastUpdated(threadID)
    }

    override fun trimThread(threadID: Long, threadLimit: Int) {
        val threadDB = threadDatabase
        threadDB.trimThread(threadID, threadLimit)
    }

    override fun trimThreadBefore(threadID: Long, timestamp: Long) {
        val threadDB = threadDatabase
        threadDB.trimThreadBefore(threadID, timestamp)
    }

    override fun getMessageCount(threadID: Long): Long {
        val mmsSmsDb = mmsSmsDatabase
        return mmsSmsDb.getConversationCount(threadID)
    }

    override fun setPinned(threadID: Long, isPinned: Boolean) {
        val threadDB = threadDatabase
        threadDB.setPinned(threadID, isPinned)
        val address = getRecipientForThread(threadID) ?: return
        val isLocalNumber = address == getUserPublicKey()?.let { fromSerialized(it) }
        configFactory.withMutableUserConfigs { configs ->
            if (isLocalNumber) {
                configs.userProfile.setNtsPriority(if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE)
            } else if (address.isContact) {
                configs.contacts.upsertContact(address.toString()) {
                    priority = if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE
                }
            } else if (address.isGroupOrCommunity) {
                when {
                    address.isLegacyGroup -> {
                        address.toString()
                            .let(GroupUtil::doubleDecodeGroupId)
                            .let(configs.userGroups::getOrConstructLegacyGroupInfo)
                            .copy(priority = if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE)
                            .let(configs.userGroups::set)
                    }

                    address.isGroupV2 -> {
                        val newGroupInfo = configs.userGroups
                            .getOrConstructClosedGroup(address.toString())
                            .copy(priority = if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE)
                        configs.userGroups.set(newGroupInfo)
                    }

                    address.isCommunity -> {
                        val openGroup = getOpenGroup(threadID) ?: return@withMutableUserConfigs
                        val (baseUrl, room, pubKeyHex) = BaseCommunityInfo.parseFullUrl(openGroup.joinURL)
                            ?: return@withMutableUserConfigs
                        val newGroupInfo = configs.userGroups.getOrConstructCommunityInfo(
                            baseUrl,
                            room,
                            Hex.toStringCondensed(pubKeyHex)
                        ).copy(priority = if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE)
                        configs.userGroups.set(newGroupInfo)
                    }
                }
            }
        }
    }

    override fun isPinned(threadID: Long): Boolean {
        val threadDB = threadDatabase
        return threadDB.isPinned(threadID)
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

    override fun deleteConversation(threadID: Long) {
        val threadDB = threadDatabase
        val groupDB = groupDatabase

        val recipientAddress = getRecipientForThread(threadID)

        // Delete the conversation and its messages
        smsDatabase.deleteThread(threadID)
        mmsDatabase.deleteThread(threadID)
        get(context).draftDatabase().clearDrafts(threadID)
        lokiMessageDatabase.deleteThread(threadID)
        threadDB.deleteThread(threadID)
        notifyConversationListeners(threadID)
        notifyConversationListListeners()
        clearReceivedMessages()

        if (recipientAddress == null) return

        configFactory.withMutableUserConfigs { configs ->
            if (recipientAddress.isGroupOrCommunity) {
                if (recipientAddress.isLegacyGroup) {
                    val accountId = GroupUtil.doubleDecodeGroupId(recipientAddress.toString())
                    groupDB.delete(recipientAddress.toString())
                    configs.convoInfoVolatile.eraseLegacyClosedGroup(accountId)
                    configs.userGroups.eraseLegacyGroup(accountId)
                } else if (recipientAddress.isCommunity) {
                    // these should be removed in the group leave / handling new configs
                    Log.w("Loki", "Thread delete called for open group address, expecting to be handled elsewhere")
                } else if (recipientAddress.isGroupV2) {
                    Log.w("Loki", "Thread delete called for closed group address, expecting to be handled elsewhere")
                }
            } else {
                // non-standard contact prefixes: 15, 00 etc shouldn't be stored in config
                if(!recipientAddress.toString().startsWith(IdPrefix.STANDARD.value)) return@withMutableUserConfigs
                configs.convoInfoVolatile.eraseOneToOne(recipientAddress.toString())

                if (getUserPublicKey() != recipientAddress.toString()) {
                    // only update the priority if the contact exists in our config
                    // (this helps for example when deleting a contact and we do not want to recreate one here only to mark it hidden)
                    configs.contacts.get(recipientAddress.toString())?.let{
                        it.priority = PRIORITY_HIDDEN
                        configs.contacts.set(it)
                    }
                } else {
                    configs.userProfile.setNtsPriority(PRIORITY_HIDDEN)
                }
            }

            Unit
        }
    }

    override fun clearMessages(threadID: Long, fromUser: Address?): Boolean {
        val threadDb = threadDatabase
        if (fromUser == null) {
            // this deletes all *from* thread, not deleting the actual thread
            smsDatabase.deleteThread(threadID)
            mmsDatabase.deleteThread(threadID) // threadDB update called from within
        } else {
            // this deletes all *from* thread, not deleting the actual thread
            smsDatabase.deleteMessagesFrom(threadID, fromUser.toString())
            mmsDatabase.deleteMessagesFrom(threadID, fromUser.toString())
            threadDb.update(threadID, false)
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

        if (recipient?.blocked == true) return
        val threadId = getThreadId(address) ?: return
        val expiresInMillis = recipient?.expiryMode?.expiryMillis ?: 0
        val expireStartedAt = if (recipient?.expiryMode is ExpiryMode.AfterSend) sentTimestamp else 0
        val mediaMessage = IncomingMediaMessage(
            address,
            sentTimestamp,
            -1,
            expiresInMillis,
            expireStartedAt,
            false,
            false,
            false,
            Optional.absent(),
            Optional.absent(),
            Optional.absent(),
            Optional.absent(),
            Optional.absent(),
            Optional.absent(),
            Optional.of(message)
        )

        mmsDatabase.insertSecureDecryptedMessageInbox(mediaMessage, threadId, runThreadUpdate = true)
            .orNull()
            ?.let {
                messageExpirationManager.startExpiringNow(MessageId(id = it.messageId, mms = true))
            }
    }

    /**
     * This will create a control message used to indicate that a contact has accepted our message request
     */
    override fun insertMessageRequestResponseFromContact(response: MessageRequestResponse) {
        val userPublicKey = getUserPublicKey()
        val senderPublicKey = response.sender!!
        val recipientPublicKey = response.recipient!!

        if (
            userPublicKey == null
            || (userPublicKey != recipientPublicKey && userPublicKey != senderPublicKey)
            // this is true if it is a sync message
            || (userPublicKey == recipientPublicKey && userPublicKey == senderPublicKey)
        ) return

        if (userPublicKey == senderPublicKey) {
            val requestRecipient = fromSerialized(recipientPublicKey)
            recipientDatabase.setApproved(requestRecipient, true)
            val threadId = threadDatabase.getOrCreateThreadIdFor(requestRecipient)
            threadDatabase.setHasSent(threadId, true)
        } else {
            val sender = fromSerialized(senderPublicKey)
            val threadId = getOrCreateThreadIdFor(sender)
            val profile = response.profile
            if (profile != null) {
                profileUpdateHandler.handleProfileUpdate(
                    sender,
                    ProfileUpdateHandler.Updates(
                        name = profile.displayName,
                        picUrl = profile.profilePictureURL,
                        picKey = profile.profileKey,
                        acceptsCommunityRequests = null
                    ),
                    communityServerPubKey = null)
            }
            
            val mappingDb = blindedIdMappingDatabase
            val mappings = mutableMapOf<String, BlindedIdMapping>()
            threadDatabase.readerFor(threadDatabase.conversationList).use { reader ->
                while (reader.next != null) {
                    val recipient = reader.current.recipient
                    val address = recipient.address.toString()
                    val blindedId = when {
                        recipient.isGroupOrCommunityRecipient -> null
                        recipient.isCommunityInboxRecipient -> GroupUtil.getDecodedOpenGroupInboxAccountId(address)
                        else -> address.takeIf { AccountId(it).prefix == IdPrefix.BLINDED }
                    } ?: continue
                    mappingDb.getBlindedIdMapping(blindedId).firstOrNull()?.let {
                        mappings[address] = it
                    }
                }
            }
            for (mapping in mappings) {
                if (!BlindKeyAPI.sessionIdMatchesBlindedId(
                        sessionId = senderPublicKey,
                        blindedId = mapping.value.blindedId,
                        serverPubKey = mapping.value.serverId
                    )
                ) {
                    continue
                }
                mappingDb.addBlindedIdMapping(mapping.value.copy(accountId = senderPublicKey))

                val blindedThreadId = threadDatabase.getOrCreateThreadIdFor(fromSerialized(mapping.key))
                mmsDatabase.updateThreadId(blindedThreadId, threadId)
                smsDatabase.updateThreadId(blindedThreadId, threadId)
                deleteConversation(blindedThreadId)
            }

            var alreadyApprovedMe = false

            // Update the contact's approval status
            configFactory.withMutableUserConfigs { configs ->
                configs.contacts.upsertContact(sender.toString()) {
                    alreadyApprovedMe = approvedMe
                    approvedMe = true
                }
            }

            // only show the message if wasn't already approvedMe before
            if(!alreadyApprovedMe) {
                val message = IncomingMediaMessage(
                    sender,
                    response.sentTimestamp!!,
                    -1,
                    0,
                    0,
                    false,
                    true,
                    false,
                    Optional.absent(),
                    Optional.absent(),
                    Optional.absent(),
                    Optional.absent(),
                    Optional.absent(),
                    Optional.absent(),
                    Optional.absent()
                )
                mmsDatabase.insertSecureDecryptedMessageInbox(
                    message,
                    threadId,
                    runThreadUpdate = true
                )
            }
        }
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
            false,
            true,
            false,
            Optional.absent(),
            Optional.absent(),
            Optional.absent(),
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
        val expiryMode = recipient?.expiryMode?.coerceSendToRead() ?: ExpiryMode.NONE
        val expiresInMillis = expiryMode.expiryMillis
        val expireStartedAt = if (expiryMode is ExpiryMode.AfterSend) sentTimestamp else 0
        val callMessage = IncomingTextMessage.fromCallInfo(callMessageType, address, Optional.absent(), sentTimestamp, expiresInMillis, expireStartedAt)
        smsDatabase.insertCallMessage(callMessage).orNull()
            ?.let {
                messageExpirationManager.startExpiringNow(MessageId(it.messageId, mms = false))
            }

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

    override fun removeLastInboxMessageId(server: String) {
        lokiAPIDatabase.removeLastInboxMessageId(server)
    }

    override fun getLastOutboxMessageId(server: String): Long? {
        return lokiAPIDatabase.getLastOutboxMessageId(server)
    }

    override fun setLastOutboxMessageId(server: String, messageId: Long) {
        lokiAPIDatabase.setLastOutboxMessageId(server, messageId)
    }

    override fun removeLastOutboxMessageId(server: String) {
        lokiAPIDatabase.removeLastOutboxMessageId(server)
    }

    override fun getOrCreateBlindedIdMapping(
        blindedId: String,
        server: String,
        serverPublicKey: String,
        fromOutbox: Boolean
    ): BlindedIdMapping {
        val db = blindedIdMappingDatabase
        val mapping = db.getBlindedIdMapping(blindedId).firstOrNull() ?: BlindedIdMapping(blindedId, null, server, serverPublicKey)
        if (mapping.accountId != null) {
            return mapping
        }

        configFactory.withUserConfigs { it.contacts.all() }
            .forEach { contact ->
                val accountId = AccountId(contact.id)
                if (accountId.prefix == IdPrefix.STANDARD && BlindKeyAPI.sessionIdMatchesBlindedId(
                        sessionId = accountId.hexString,
                        blindedId = blindedId,
                        serverPubKey = serverPublicKey
                    )
                ) {
                    val contactMapping = mapping.copy(accountId = accountId.hexString)
                    db.addBlindedIdMapping(contactMapping)
                    return contactMapping
                }
            }
        db.getBlindedIdMappingsExceptFor(server).forEach {
            if (BlindKeyAPI.sessionIdMatchesBlindedId(
                    sessionId = it.accountId!!,
                    blindedId = blindedId,
                    serverPubKey = serverPublicKey
                )
            ) {
                val otherMapping = mapping.copy(accountId = it.accountId)
                db.addBlindedIdMapping(otherMapping)
                return otherMapping
            }
        }
        db.addBlindedIdMapping(mapping)
        return mapping
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

        addReaction(messageId, reaction, messageSender, notifyUnread)
    }

    override fun addReaction(messageId: MessageId, reaction: Reaction, messageSender: String, notifyUnread: Boolean) {
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
            ),
            notifyUnread
        )
    }

    override fun addReactions(
        reactions: Map<MessageId, List<ReactionRecord>>,
        replaceAll: Boolean,
        notifyUnread: Boolean
    ) {
        reactionDatabase.addReactions(
            reactionsByMessageId = reactions,
            replaceAll = replaceAll,
            notifyUnread = notifyUnread
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
        reactionDatabase.deleteReaction(emoji, MessageId(messageRecord.id, messageRecord.isMms), author, notifyUnread)
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

    override fun setBlocked(recipients: Iterable<Address>, isBlocked: Boolean, fromConfigUpdate: Boolean) {
        val recipientDb = recipientDatabase
        recipientDb.setBlocked(recipients, isBlocked)

        if (!fromConfigUpdate) {
            val currentUserKey = getUserPublicKey()
            configFactory.withMutableUserConfigs { configs ->
                recipients.filter { it.isContact && (it.toString() != currentUserKey) }
                    .forEach { recipient ->
                        configs.contacts.upsertContact(recipient.toString()) {
                            this.blocked = isBlocked
                        }
                    }
            }
        }
    }

    override fun blockedContacts(): List<Recipient> {
        val allBlockedContacts = hashSetOf<Address>()

        // Source data from config first
        configFactory.withUserConfigs {
            it.contacts.all()
        }.asSequence()
            .filter { it.blocked }
            .mapTo(allBlockedContacts) { Address.fromSerialized(it.id) }

        // Source data from the local database. This might contain something that is not synced
        // to the config system.e
        allBlockedContacts.addAll(recipientDatabase.blockedContacts)

        return allBlockedContacts.map {
            recipientRepository.getRecipientSync(it) ?: Recipient.empty(it)
        }
    }

    override fun getExpirationConfiguration(threadId: Long): ExpiryMode {
        val recipient = getRecipientForThread(threadId) ?: return ExpiryMode.NONE

        return recipientRepository.getRecipientSync(recipient)?.expiryMode ?: ExpiryMode.NONE
    }

    override fun setExpirationConfiguration(threadId: Long, expiryMode: ExpiryMode) {
        val recipient = getRecipientForThread(threadId) ?: return

        if (expiryMode == ExpiryMode.NONE) {
            // Clear the legacy recipients on updating config to be none
            lokiAPIDatabase.setLastLegacySenderAddress(recipient.toString(), null)
        }

        if (recipient.isLegacyGroup) {
            val groupPublicKey = GroupUtil.addressToGroupAccountId(recipient)

            configFactory.withMutableUserConfigs {
                val groupInfo = it.userGroups.getLegacyGroupInfo(groupPublicKey)
                    ?.copy(disappearingTimer = expiryMode.expirySeconds) ?: return@withMutableUserConfigs
                it.userGroups.set(groupInfo)
            }
        } else if (recipient.isGroupV2) {
            val groupSessionId = AccountId(recipient.toString())
            configFactory.withMutableGroupConfigs(groupSessionId) { configs ->
                configs.groupInfo.setExpiryTimer(expiryMode.expirySeconds)
            }

        } else if (recipient.address == getUserPublicKey()) {
            configFactory.withMutableUserConfigs {
                it.userProfile.setNtsExpiry(expiryMode)
            }
        } else if (recipient.isContact) {
            configFactory.withMutableUserConfigs {
                val contact = it.contacts.get(recipient.address.toString())?.copy(expiryMode = expiryMode) ?: return@withMutableUserConfigs
                it.contacts.set(contact)
            }
        }
    }

    override fun getExpiringMessages(messageIds: List<Long>): List<Pair<Long, Long>> {
        val expiringMessages = mutableListOf<Pair<Long, Long>>()
        val smsDb = smsDatabase
        smsDb.readerFor(smsDb.expirationNotStartedMessages).use { reader ->
            while (reader.next != null) {
                if (messageIds.isEmpty() || reader.current.id in messageIds) {
                    expiringMessages.add(reader.current.id to reader.current.expiresIn)
                }
            }
        }
        val mmsDb = mmsDatabase
        mmsDb.expireNotStartedMessages.use { reader ->
            while (reader.next != null) {
                if (messageIds.isEmpty() || reader.current.id in messageIds) {
                    expiringMessages.add(reader.current.id to reader.current.expiresIn)
                }
            }
        }
        return expiringMessages
    }
}

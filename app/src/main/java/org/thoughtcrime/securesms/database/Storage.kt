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
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.BlindedIdMapping
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.GroupAvatarDownloadJob
import org.session.libsession.messaging.jobs.Job
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveJob
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.jobs.RetrieveProfileAvatarJob
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.ConfigurationMessage
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
import org.session.libsession.utilities.ProfileKeyUtil
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.Recipient.DisappearingState
import org.session.libsession.utilities.upsertContact
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
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
import java.security.MessageDigest
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
    private val sessionContactDatabase: SessionContactDatabase,
    private val expirationConfigurationDatabase: ExpirationConfigurationDatabase,
    private val profileManager: SSKEnvironment.ProfileManagerProtocol,
    private val notificationManager: MessageNotifier,
    private val messageDataProvider: MessageDataProvider,
    private val messageExpirationManager: SSKEnvironment.MessageExpirationManagerProtocol,
    private val clock: SnodeClock,
    private val preferences: TextSecurePreferences,
    private val usernameUtils: UsernameUtils,
    private val openGroupManager: Lazy<OpenGroupManager>,
) : Database(context, helper), StorageProtocol, ThreadDatabase.ConversationThreadUpdateListener {

    init {
        threadDatabase.setUpdateListener(this)
    }

    override fun threadCreated(address: Address, threadId: Long) {
        val localUserAddress = getUserPublicKey() ?: return
        if (!getRecipientApproved(address) && localUserAddress != address.toString()) return // don't store unapproved / message requests

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
        val displayName = usernameUtils.getCurrentUsername()
        val profileKey = ProfileKeyUtil.getProfileKey(context)
        val profilePictureUrl = TextSecurePreferences.getProfilePictureURL(context)
        return Profile(displayName, profileKey, profilePictureUrl)
    }

    override fun setProfilePicture(recipient: Recipient, newProfilePicture: String?, newProfileKey: ByteArray?) {
        val db = recipientDatabase
        db.setProfileAvatar(recipient, newProfilePicture)
        db.setProfileKey(recipient, newProfileKey)
    }

    override fun setBlocksCommunityMessageRequests(recipient: Recipient, blocksMessageRequests: Boolean) {
        val db = recipientDatabase
        db.setBlocksCommunityMessageRequests(recipient, blocksMessageRequests)
    }

    override fun setUserProfilePicture(newProfilePicture: String?, newProfileKey: ByteArray?) {
        val ourRecipient = fromSerialized(getUserPublicKey()!!).let {
            Recipient.from(context, it, false)
        }
        ourRecipient.resolve().profileKey = newProfileKey
        preferences.setProfileKey(newProfileKey?.let { Base64.encodeBytes(it) })
        preferences.setProfilePictureURL(newProfilePicture)

        if (newProfileKey != null) {
            JobQueue.shared.add(RetrieveProfileAvatarJob(newProfilePicture, ourRecipient.address, newProfileKey))
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
            if (!threadDb.markAllAsRead(threadId, recipient.isGroupOrCommunityRecipient, lastSeenTime, force) && !force) return

            // don't process configs for inbox recipients
            if (recipient.isCommunityInboxRecipient) return

            configFactory.withMutableUserConfigs { configs ->
                val config = configs.convoInfoVolatile
                val convo = when {
                    // recipient closed group
                    recipient.isLegacyGroupRecipient -> config.getOrConstructLegacyGroup(GroupUtil.doubleDecodeGroupId(recipient.address.toString()))
                    recipient.isGroupV2Recipient -> config.getOrConstructClosedGroup(recipient.address.toString())
                    // recipient is open group
                    recipient.isCommunityRecipient -> {
                        val openGroupJoinUrl = getOpenGroup(threadId)?.joinURL ?: return@withMutableUserConfigs
                        BaseCommunityInfo.parseFullUrl(openGroupJoinUrl)?.let { (base, room, pubKey) ->
                            config.getOrConstructCommunity(base, room, pubKey)
                        } ?: return@withMutableUserConfigs
                    }
                    // otherwise recipient is one to one
                    recipient.isContactRecipient -> {
                        // don't process non-standard account IDs though
                        if (IdPrefix.fromValue(recipient.address.toString()) != IdPrefix.STANDARD) return@withMutableUserConfigs
                        config.getOrConstructOneToOne(recipient.address.toString())
                    }
                    else -> throw NullPointerException("Weren't expecting to have a convo with address ${recipient.address.toString()}")
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
        val targetRecipient = Recipient.from(context, targetAddress, false)
        if (!targetRecipient.isGroupOrCommunityRecipient) {
            if (isUserSender || isUserBlindedSender) {
                setRecipientApproved(targetRecipient, true)
            } else {
                setRecipientApprovedMe(targetRecipient, true)
            }
        }
        if (message.threadID == null && !targetRecipient.isCommunityRecipient) {
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
                    targetRecipient,
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
                val textMessage = if (isOpenGroupInvitation) OutgoingTextMessage.fromOpenGroupInvitation(message.openGroupInvitation, targetRecipient, message.sentTimestamp, expiresInMillis, expireStartedAt)
                else OutgoingTextMessage.from(message, targetRecipient, expiresInMillis, expireStartedAt)
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

    override fun clearUserPic(clearConfig: Boolean) {
        val userPublicKey = getUserPublicKey() ?: return Log.w(TAG, "No user public key when trying to clear user pic")
        val recipient = Recipient.from(context, fromSerialized(userPublicKey), false)

        // Clear details related to the user's profile picture
        preferences.setProfileKey(null)
        ProfileKeyUtil.setEncodedProfileKey(context, null)
        recipientDatabase.setProfileAvatar(recipient, null)
        preferences.setProfileAvatarId(0)
        preferences.setProfilePictureURL(null)

        Recipient.removeCached(fromSerialized(userPublicKey)) // ACL HERE?!?!?!
        if (clearConfig) {
            configFactory.withMutableUserConfigs {
                it.userProfile.setPic(UserPic.DEFAULT)
            }
        }
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
        if (threadId.toInt() < 0) { return null }
        val database = readableDatabase
        return database.get(LokiThreadDatabase.publicChatTable, "${LokiThreadDatabase.threadID} = ?", arrayOf( threadId.toString() )) { cursor ->
            val publicChatAsJson = cursor.getString(LokiThreadDatabase.publicChat)
            OpenGroup.fromJSON(publicChatAsJson)
        }
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

    override fun updateGroupConfig(groupPublicKey: String) {
        val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
        val groupAddress = fromSerialized(groupID)
        val existingGroup = getGroup(groupID)
            ?: return Log.w("Loki-DBG", "No existing group for ${groupPublicKey.take(4)}} when updating group config")
        configFactory.withMutableUserConfigs {
            val userGroups = it.userGroups
            if (!existingGroup.isActive) {
                userGroups.eraseLegacyGroup(groupPublicKey)
                return@withMutableUserConfigs
            }
            val name = existingGroup.title
            val admins = existingGroup.admins.map { it.toString() }
            val members = existingGroup.members.map { it.toString() }
            val membersMap = GroupUtil.createConfigMemberMap(admins = admins, members = members)
            val latestKeyPair = getLatestClosedGroupEncryptionKeyPair(groupPublicKey)
                ?: return@withMutableUserConfigs Log.w("Loki-DBG", "No latest closed group encryption key pair for ${groupPublicKey.take(4)}} when updating group config")

            val threadID = getThreadId(groupAddress) ?: return@withMutableUserConfigs
            val groupInfo = userGroups.getOrConstructLegacyGroupInfo(groupPublicKey).copy(
                name = name,
                members = membersMap,
                encPubKey = Bytes((latestKeyPair.publicKey as DjbECPublicKey).publicKey),  // 'serialize()' inserts an extra byte
                encSecKey = Bytes(latestKeyPair.privateKey.serialize()),
                priority = if (isPinned(threadID)) PRIORITY_PINNED else PRIORITY_VISIBLE,
                disappearingTimer = getExpirationConfiguration(threadID)?.expiryMode?.expirySeconds ?: 0L,
                joinedAtSecs = (existingGroup.formationTimestamp / 1000L)
            )
            userGroups.set(groupInfo)
        }
    }

    override fun isGroupActive(groupPublicKey: String): Boolean {
        return groupDatabase.getGroup(GroupUtil.doubleEncodeGroupID(groupPublicKey)).orNull()?.isActive == true
    }

    override fun setActive(groupID: String, value: Boolean) {
        groupDatabase.setActive(groupID, value)
    }

    override fun getZombieMembers(groupID: String): Set<String> {
        return groupDatabase.getGroupZombieMembers(groupID).map { it.address.toString() }.toHashSet()
    }

    override fun removeMember(groupID: String, member: Address) {
        groupDatabase.removeMember(groupID, member)
    }

    override fun updateMembers(groupID: String, members: List<Address>) {
        groupDatabase.updateMembers(groupID, members)
    }

    override fun setZombieMembers(groupID: String, members: List<Address>) {
        groupDatabase.updateZombieMembers(groupID, members)
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
        val recipient = Recipient.from(context, fromSerialized(groupID), false)
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

    override fun insertGroupInfoChange(message: GroupUpdated, closedGroup: AccountId): Long? {
        val sentTimestamp = message.sentTimestamp ?: clock.currentTimeMills()
        val senderPublicKey = message.sender
        val groupName = configFactory.withGroupConfigs(closedGroup) { it.groupInfo.getName() }
            ?: configFactory.getGroup(closedGroup)?.name

        val updateData = UpdateMessageData.buildGroupUpdate(message, groupName.orEmpty()) ?: return null

        return insertUpdateControlMessage(updateData, sentTimestamp, senderPublicKey, closedGroup)
    }

    override fun insertGroupInfoLeaving(closedGroup: AccountId): Long? {
        val sentTimestamp = clock.currentTimeMills()
        val senderPublicKey = getUserPublicKey() ?: return null
        val updateData = UpdateMessageData.buildGroupLeaveUpdate(UpdateMessageData.Kind.GroupLeaving)

        return insertUpdateControlMessage(updateData, sentTimestamp, senderPublicKey, closedGroup)
    }

    override fun insertGroupInfoErrorQuit(closedGroup: AccountId): Long? {
        val sentTimestamp = clock.currentTimeMills()
        val senderPublicKey = getUserPublicKey() ?: return null
        val groupName = configFactory.withGroupConfigs(closedGroup) { it.groupInfo.getName() }
            ?: configFactory.getGroup(closedGroup)?.name
        val updateData = UpdateMessageData.buildGroupLeaveUpdate(UpdateMessageData.Kind.GroupErrorQuit(groupName.orEmpty()))

        return insertUpdateControlMessage(updateData, sentTimestamp, senderPublicKey, closedGroup)
    }

    override fun updateGroupInfoChange(messageId: Long, newType: UpdateMessageData.Kind) {
        val mmsDB = mmsDatabase
        val newMessage = UpdateMessageData.buildGroupLeaveUpdate(newType)
        mmsDB.updateInfoMessage(messageId, newMessage.toJSON())
    }

    override fun deleteGroupInfoMessages(groupId: AccountId, kind: Class<out UpdateMessageData.Kind>) {
        mmsSmsDatabase.deleteGroupInfoMessage(groupId, kind)
    }

    override fun insertGroupInviteControlMessage(sentTimestamp: Long, senderPublicKey: String, senderName: String?, closedGroup: AccountId, groupName: String): Long? {
        val updateData = UpdateMessageData(UpdateMessageData.Kind.GroupInvitation(
            groupAccountId = closedGroup.hexString,
            invitingAdminId = senderPublicKey,
            invitingAdminName = senderName,
            groupName = groupName
        ))
        return insertUpdateControlMessage(updateData, sentTimestamp, senderPublicKey, closedGroup)
    }

    private fun insertUpdateControlMessage(updateData: UpdateMessageData, sentTimestamp: Long, senderPublicKey: String?, closedGroup: AccountId): Long? {
        val userPublicKey = getUserPublicKey()!!
        val recipient = Recipient.from(context, fromSerialized(closedGroup.hexString), false)
        val threadDb = threadDatabase
        val threadID = threadDb.getThreadIdIfExistsFor(recipient)
        val expirationConfig = getExpirationConfiguration(threadID)
        val expiryMode = expirationConfig?.expiryMode
        val expiresInMillis = expiryMode?.expiryMillis ?: 0
        val expireStartedAt = if (expiryMode is ExpiryMode.AfterSend) sentTimestamp else 0
        val inviteJson = updateData.toJSON()


        if (senderPublicKey == null || senderPublicKey == userPublicKey) {
            val infoMessage = OutgoingGroupMediaMessage(
                recipient,
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
            return infoMessageID
        } else {
            val group = SignalServiceGroup(Hex.fromStringCondensed(closedGroup.hexString), SignalServiceGroup.GroupType.SIGNAL)
            val m = IncomingTextMessage(fromSerialized(senderPublicKey), 1, sentTimestamp, "", Optional.of(group), expiresInMillis, expireStartedAt, true, false)
            val infoMessage = IncomingGroupMessage(m, inviteJson, true)
            val smsDB = smsDatabase
            val insertResult = smsDB.insertMessageInbox(infoMessage,  true)
            return insertResult.orNull()?.messageId
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

    override fun setProfileSharing(address: Address, value: Boolean) {
        val recipient = Recipient.from(context, address, false)
        recipientDatabase.setProfileSharing(recipient, value)
    }

    override fun getOrCreateThreadIdFor(address: Address): Long {
        val recipient = Recipient.from(context, address, false)
        return threadDatabase.getOrCreateThreadIdFor(recipient)
    }

    override fun getThreadIdFor(publicKey: String, groupPublicKey: String?, openGroupID: String?, createThread: Boolean): Long? {
        val database = threadDatabase
        return if (!openGroupID.isNullOrEmpty()) {
            val recipient = Recipient.from(context, fromSerialized(GroupUtil.getEncodedOpenGroupID(openGroupID.toByteArray())), false)
            database.getThreadIdIfExistsFor(recipient).let { if (it == -1L) null else it }
        } else if (!groupPublicKey.isNullOrEmpty() && !groupPublicKey.startsWith(IdPrefix.GROUP.value)) {
            val recipient = Recipient.from(context, fromSerialized(GroupUtil.doubleEncodeGroupID(groupPublicKey)), false)
            if (createThread) database.getOrCreateThreadIdFor(recipient)
            else database.getThreadIdIfExistsFor(recipient).let { if (it == -1L) null else it }
        } else if (!groupPublicKey.isNullOrEmpty()) {
            val recipient = Recipient.from(context, fromSerialized(groupPublicKey), false)
            if (createThread) database.getOrCreateThreadIdFor(recipient)
            else database.getThreadIdIfExistsFor(recipient).let { if (it == -1L) null else it }
        } else {
            val recipient = Recipient.from(context, fromSerialized(publicKey), false)
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
        val recipient = Recipient.from(context, address, false)
        return getThreadId(recipient)
    }

    override fun getThreadId(recipient: Recipient): Long? {
        val threadID = threadDatabase.getThreadIdIfExistsFor(recipient)
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

    override fun getContactWithAccountID(accountID: String): Contact? {
        return sessionContactDatabase.getContactWithAccountID(accountID)
    }

    override fun getAllContacts(): Set<Contact> {
        return sessionContactDatabase.getAllContacts()
    }

    override fun setContact(contact: Contact) {
        sessionContactDatabase.setContact(contact)
        val address = fromSerialized(contact.accountID)
        if (!getRecipientApproved(address)) return
        val recipientHash = profileManager.contactUpdatedInternal(contact)
        val recipient = Recipient.from(context, address, false)
        setRecipientHash(recipient, recipientHash)
    }

    override fun deleteContactAndSyncConfig(accountId: String) {
        deleteContact(accountId)
        // also handle the contact removal from the config's point of view
        configFactory.removeContact(accountId)
    }

    private fun deleteContact(accountId: String){
        sessionContactDatabase.deleteContact(accountId)
        Recipient.removeCached(fromSerialized(accountId))
        recipientDatabase.deleteRecipient(accountId)

        val threadId: Long = threadDatabase.getThreadIdIfExistsFor(accountId)
        deleteConversation(threadId)

        notifyRecipientListeners()
    }

    override fun getRecipientForThread(threadId: Long): Recipient? {
        return threadDatabase.getRecipientForThreadId(threadId)
    }

    override fun getRecipientSettings(address: Address): Recipient.RecipientSettings? {
        return recipientDatabase.getRecipientSettings(address).orNull()
    }

    override fun hasAutoDownloadFlagBeenSet(recipient: Recipient): Boolean {
        return recipientDatabase.isAutoDownloadFlagSet(recipient)
    }

    override fun syncLibSessionContacts(contacts: List<LibSessionContact>, timestamp: Long?) {
        val mappingDb = blindedIdMappingDatabase
        val moreContacts = contacts.filter { contact ->
            val id = AccountId(contact.id)
            id.prefix?.isBlinded() == false || mappingDb.getBlindedIdMapping(contact.id).none { it.accountId != null }
        }
        moreContacts.forEach { contact ->
            val address = fromSerialized(contact.id)
            val recipient = Recipient.from(context, address, false)
            setBlocked(listOf(recipient), contact.blocked, fromConfigUpdate = true)
            setRecipientApproved(recipient, contact.approved)
            setRecipientApprovedMe(recipient, contact.approvedMe)
            if (contact.name.isNotEmpty()) {
                profileManager.setName(context, recipient, contact.name)
            } else {
                profileManager.setName(context, recipient, null)
            }
            if (contact.nickname.isNotEmpty()) {
                profileManager.setNickname(context, recipient, contact.nickname)
            } else {
                profileManager.setNickname(context, recipient, null)
            }

            if (contact.profilePicture != UserPic.DEFAULT) {
                val (url, key) = contact.profilePicture
                if (key.data.size != ProfileKeyUtil.PROFILE_KEY_BYTES) return@forEach
                profileManager.setProfilePicture(context, recipient, url, key.data)
                profileManager.setUnidentifiedAccessMode(context, recipient, Recipient.UnidentifiedAccessMode.UNKNOWN)
            } else {
                profileManager.setProfilePicture(context, recipient, null, null)
            }
            if (contact.priority == PRIORITY_HIDDEN) {
                getThreadId(fromSerialized(contact.id))?.let(::deleteConversation)
            } else {
                (
                    getThreadId(address) ?: getOrCreateThreadIdFor(address).also {
                        setThreadCreationDate(it, 0)
                    }
                ).also { setPinned(it, contact.priority == PRIORITY_PINNED) }
            }
            if (timestamp != null) {
                getThreadId(recipient)?.let {
                    setExpirationConfiguration(
                        getExpirationConfiguration(it)?.takeIf { it.updatedTimestampMs > timestamp }
                            ?: ExpirationConfiguration(it, contact.expiryMode, timestamp)
                    )
                }
            }
            setRecipientHash(recipient, contact.hashCode().toString())
        }

        // if we have contacts locally but that are missing from the config, remove their corresponding thread
        val currentUserKey = getUserPublicKey()

        //NOTE: We used to cycle through all Contact here instead or all Recipients, but turns out a Contact isn't saved until we have a name, nickname or avatar
        // which in the case of contacts we are messaging for the first time and who haven't yet approved us, it won't be the case
        // But that person is saved in the Recipient db. We might need to investigate how to clean the relationship between Recipients, Contacts and config Contacts.
        val removedContacts = recipientDatabase.allRecipients.filter { localContact ->
            IdPrefix.fromValue(localContact.address.toString()) == IdPrefix.STANDARD && // only want standard address
            localContact.is1on1 && // only for conversations
            localContact.address.toString() != currentUserKey && // we don't want to remove ourselves (ie, our Note to Self)
            moreContacts.none { it.id == localContact.address.toString() } // we don't want to remove contacts that are present in the config
        }
        removedContacts.forEach {
            deleteContact(it.address.toString())
        }
    }

    override fun addContacts(contacts: List<ConfigurationMessage.Contact>) {
        val recipientDatabase = recipientDatabase
        val threadDatabase = threadDatabase
        val mappingDb = blindedIdMappingDatabase
        val moreContacts = contacts.filter { contact ->
            IdPrefix.fromValue(contact.publicKey) != IdPrefix.BLINDED ||
                    mappingDb.getBlindedIdMapping(contact.publicKey).none { it.accountId != null }
        }
        for (contact in moreContacts) {
            val address = fromSerialized(contact.publicKey)
            val recipient = Recipient.from(context, address, true)
            if (!contact.profilePicture.isNullOrEmpty()) {
                recipientDatabase.setProfileAvatar(recipient, contact.profilePicture)
            }
            if (contact.profileKey?.isNotEmpty() == true) {
                recipientDatabase.setProfileKey(recipient, contact.profileKey)
            }
            if (contact.name.isNotEmpty()) {
                recipientDatabase.setProfileName(recipient, contact.name)
            }
            recipientDatabase.setProfileSharing(recipient, true)
            recipientDatabase.setRegistered(recipient, Recipient.RegisteredState.REGISTERED)
            // create Thread if needed
            val threadId = threadDatabase.getThreadIdIfExistsFor(recipient)
            if (contact.didApproveMe == true) {
                recipientDatabase.setApprovedMe(recipient, true)
            }
            if (contact.isApproved == true && threadId != -1L) {
                setRecipientApproved(recipient, true)
                threadDatabase.setHasSent(threadId, true)
            }

            val contactIsBlocked: Boolean? = contact.isBlocked
            if (contactIsBlocked != null && recipient.isBlocked != contactIsBlocked) {
                setBlocked(listOf(recipient), contactIsBlocked, fromConfigUpdate = true)
            }
        }
        if (contacts.isNotEmpty()) {
            threadDatabase.notifyConversationListListeners()
        }
    }

    override fun shouldAutoDownloadAttachments(recipient: Recipient): Boolean {
        return recipient.autoDownloadAttachments
    }

    override fun setAutoDownloadAttachments(
        recipient: Recipient,
        shouldAutoDownloadAttachments: Boolean
    ) {
        val recipientDb = recipientDatabase
        recipientDb.setAutoDownloadAttachments(recipient, shouldAutoDownloadAttachments)
    }

    override fun setRecipientHash(recipient: Recipient, recipientHash: String?) {
        val recipientDb = recipientDatabase
        recipientDb.setRecipientHash(recipient, recipientHash)
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
        val threadRecipient = getRecipientForThread(threadID) ?: return
        configFactory.withMutableUserConfigs { configs ->
            if (threadRecipient.isLocalNumber) {
                configs.userProfile.setNtsPriority(if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE)
            } else if (threadRecipient.isContactRecipient) {
                configs.contacts.upsertContact(threadRecipient.address.toString()) {
                    priority = if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE
                }
            } else if (threadRecipient.isGroupOrCommunityRecipient) {
                when {
                    threadRecipient.isLegacyGroupRecipient -> {
                        threadRecipient.address.toString()
                            .let(GroupUtil::doubleDecodeGroupId)
                            .let(configs.userGroups::getOrConstructLegacyGroupInfo)
                            .copy(priority = if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE)
                            .let(configs.userGroups::set)
                    }

                    threadRecipient.isGroupV2Recipient -> {
                        val newGroupInfo = configs.userGroups
                            .getOrConstructClosedGroup(threadRecipient.address.toString())
                            .copy(priority = if (isPinned) PRIORITY_PINNED else PRIORITY_VISIBLE)
                        configs.userGroups.set(newGroupInfo)
                    }

                    threadRecipient.isCommunityRecipient -> {
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

        val recipientAddress = getRecipientForThread(threadID)?.address

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
        val database = mmsDatabase
        val address = fromSerialized(senderPublicKey)
        val recipient = Recipient.from(context, address, false)

        if (recipient.isBlocked) return
        val threadId = getThreadId(recipient) ?: return
        val expirationConfig = getExpirationConfiguration(threadId)
        val expiryMode = expirationConfig?.expiryMode ?: ExpiryMode.NONE
        val expiresInMillis = expiryMode.expiryMillis
        val expireStartedAt = if (expiryMode is ExpiryMode.AfterSend) sentTimestamp else 0
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

        database.insertSecureDecryptedMessageInbox(mediaMessage, threadId, runThreadUpdate = true)
        messageExpirationManager.maybeStartExpiration(sentTimestamp, senderPublicKey, expiryMode)
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
            val requestRecipient = Recipient.from(context, fromSerialized(recipientPublicKey), false)
            recipientDatabase.setApproved(requestRecipient, true)
            val threadId = threadDatabase.getOrCreateThreadIdFor(requestRecipient)
            threadDatabase.setHasSent(threadId, true)
        } else {
            val sender = Recipient.from(context, fromSerialized(senderPublicKey), false)
            val threadId = getOrCreateThreadIdFor(sender.address)
            val profile = response.profile
            if (profile != null) {
                val name = profile.displayName!!
                if (name.isNotEmpty()) {
                    profileManager.setName(context, sender, name)
                }
                val newProfileKey = profile.profileKey

                val needsProfilePicture = !AvatarHelper.avatarFileExists(context, sender.address)
                val profileKeyValid = newProfileKey?.isNotEmpty() == true && (newProfileKey.size == 16 || newProfileKey.size == 32) && profile.profilePictureURL?.isNotEmpty() == true
                val profileKeyChanged = (sender.profileKey == null || !MessageDigest.isEqual(sender.profileKey, newProfileKey))

                if ((profileKeyValid && profileKeyChanged) || (profileKeyValid && needsProfilePicture)) {
                    profileManager.setProfilePicture(context, sender, profile.profilePictureURL!!, newProfileKey!!)
                    profileManager.setUnidentifiedAccessMode(context, sender, Recipient.UnidentifiedAccessMode.UNKNOWN)
                }
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

                val blindedThreadId = threadDatabase.getOrCreateThreadIdFor(Recipient.from(context, fromSerialized(mapping.key), false))
                mmsDatabase.updateThreadId(blindedThreadId, threadId)
                smsDatabase.updateThreadId(blindedThreadId, threadId)
                deleteConversation(blindedThreadId)
            }

            var alreadyApprovedMe: Boolean = false
            configFactory.withUserConfigs {
                // check is the person had not yet approvedMe
                alreadyApprovedMe = it.contacts.get(sender.address.toString())?.approvedMe ?: false
            }

            setRecipientApprovedMe(sender, true)

            // only show the message if wasn't already approvedMe before
            if(!alreadyApprovedMe) {
                val message = IncomingMediaMessage(
                    sender.address,
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

    override fun getRecipientApproved(address: Address): Boolean {
        return address.isGroupV2 || recipientDatabase.getApproved(address)
    }

    override fun setRecipientApproved(recipient: Recipient, approved: Boolean) {
        recipientDatabase.setApproved(recipient, approved)
        if (recipient.isLocalNumber || !recipient.isContactRecipient) return
        configFactory.withMutableUserConfigs {
            it.contacts.upsertContact(recipient.address.toString()) {
                // if the contact wasn't approved before but is approved now, make sure it's visible
                if(approved && !this.approved) this.priority = PRIORITY_VISIBLE

                // update approval
                this.approved = approved
            }
        }
    }

    override fun setRecipientApprovedMe(recipient: Recipient, approvedMe: Boolean) {
        recipientDatabase.setApprovedMe(recipient, approvedMe)
        if (recipient.isLocalNumber || !recipient.isContactRecipient) return
        configFactory.withMutableUserConfigs {
            it.contacts.upsertContact(recipient.address.toString()) {
                this.approvedMe = approvedMe
            }
        }
    }

    override fun insertCallMessage(senderPublicKey: String, callMessageType: CallMessageType, sentTimestamp: Long) {
        val database = smsDatabase
        val address = fromSerialized(senderPublicKey)
        val recipient = Recipient.from(context, address, false)
        val threadId = threadDatabase.getOrCreateThreadIdFor(recipient)
        val expirationConfig = getExpirationConfiguration(threadId)
        val expiryMode = expirationConfig?.expiryMode?.coerceSendToRead() ?: ExpiryMode.NONE
        val expiresInMillis = expiryMode.expiryMillis
        val expireStartedAt = if (expiryMode is ExpiryMode.AfterSend) sentTimestamp else 0
        val callMessage = IncomingTextMessage.fromCallInfo(callMessageType, address, Optional.absent(), sentTimestamp, expiresInMillis, expireStartedAt)
        database.insertCallMessage(callMessage)
        messageExpirationManager.maybeStartExpiration(sentTimestamp, senderPublicKey, expiryMode)
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
        getAllContacts().forEach { contact ->
            val accountId = AccountId(contact.accountID)
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

    override fun setBlocked(recipients: Iterable<Recipient>, isBlocked: Boolean, fromConfigUpdate: Boolean) {
        val recipientDb = recipientDatabase
        recipientDb.setBlocked(recipients, isBlocked)

        if (!fromConfigUpdate) {
            configFactory.withMutableUserConfigs { configs ->
                recipients.filter { it.isContactRecipient && !it.isLocalNumber }
                    .forEach { recipient ->
                        configs.contacts.upsertContact(recipient.address.toString()) {
                            this.blocked = isBlocked
                        }
                    }
            }
        }
    }

    override fun blockedContacts(): List<Recipient> {
        val recipientDb = recipientDatabase
        return recipientDb.blockedContacts
    }

    override fun getExpirationConfiguration(threadId: Long): ExpirationConfiguration? {
        val recipient = getRecipientForThread(threadId) ?: return null
        val dbExpirationMetadata = expirationConfigurationDatabase.getExpirationConfiguration(threadId)
        return when {
            recipient.isLocalNumber -> configFactory.withUserConfigs { it.userProfile.getNtsExpiry() }
            recipient.isContactRecipient -> {
                // read it from contacts config if exists
                recipient.address.toString().takeIf { it.startsWith(IdPrefix.STANDARD.value) }
                    ?.let { configFactory.withUserConfigs { configs -> configs.contacts.get(it)?.expiryMode } }
            }
            recipient.isGroupV2Recipient -> {
                configFactory.withGroupConfigs(AccountId(recipient.address.toString())) { configs ->
                    configs.groupInfo.getExpiryTimer()
                }.let {
                    if (it == 0L) ExpiryMode.NONE else ExpiryMode.AfterSend(it)
                }
            }
            recipient.isLegacyGroupRecipient -> {
                // read it from group config if exists
                GroupUtil.doubleDecodeGroupId(recipient.address.toString())
                    .let { id -> configFactory.withUserConfigs { it.userGroups.getLegacyGroupInfo(id) } }
                    ?.run { disappearingTimer.takeIf { it != 0L }?.let(ExpiryMode::AfterSend) ?: ExpiryMode.NONE }
            }
            else -> null
        }?.let { ExpirationConfiguration(
            threadId,
            it,
            // This will be 0L for new closed groups, apparently we don't need this anymore?
            dbExpirationMetadata?.updatedTimestampMs ?: 0L
        ) }
    }

    override fun setExpirationConfiguration(config: ExpirationConfiguration) {
        val recipient = getRecipientForThread(config.threadId) ?: return

        val expirationDb = expirationConfigurationDatabase
        val currentConfig = expirationDb.getExpirationConfiguration(config.threadId)
        if (currentConfig != null && currentConfig.updatedTimestampMs >= config.updatedTimestampMs) return
        val expiryMode = config.expiryMode

        if (expiryMode == ExpiryMode.NONE) {
            // Clear the legacy recipients on updating config to be none
            lokiAPIDatabase.setLastLegacySenderAddress(recipient.address.toString(), null)
        }

        if (recipient.isLegacyGroupRecipient) {
            val groupPublicKey = GroupUtil.addressToGroupAccountId(recipient.address)

            configFactory.withMutableUserConfigs {
                val groupInfo = it.userGroups.getLegacyGroupInfo(groupPublicKey)
                    ?.copy(disappearingTimer = expiryMode.expirySeconds) ?: return@withMutableUserConfigs
                it.userGroups.set(groupInfo)
            }
        } else if (recipient.isGroupV2Recipient) {
            val groupSessionId = AccountId(recipient.address.toString())
            configFactory.withMutableGroupConfigs(groupSessionId) { configs ->
                configs.groupInfo.setExpiryTimer(expiryMode.expirySeconds)
            }

        } else if (recipient.isLocalNumber) {
            configFactory.withMutableUserConfigs {
                it.userProfile.setNtsExpiry(expiryMode)
            }
        } else if (recipient.isContactRecipient) {
            configFactory.withMutableUserConfigs {
                val contact = it.contacts.get(recipient.address.toString())?.copy(expiryMode = expiryMode) ?: return@withMutableUserConfigs
                it.contacts.set(contact)
            }
        }
        expirationDb.setExpirationConfiguration(
            config.run { copy(expiryMode = expiryMode) }
        )
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

    override fun updateDisappearingState(
        messageSender: String,
        threadID: Long,
        disappearingState: Recipient.DisappearingState
    ) {
        val threadDb = threadDatabase
        val lokiDb = lokiAPIDatabase
        val recipient = threadDb.getRecipientForThreadId(threadID) ?: return
        val recipientAddress = recipient.address.toString()
        recipientDatabase
            .setDisappearingState(recipient, disappearingState);
        val currentLegacyRecipient = lokiDb.getLastLegacySenderAddress(recipientAddress)
        val currentExpiry = getExpirationConfiguration(threadID)
        if (disappearingState == DisappearingState.LEGACY
            && currentExpiry?.isEnabled == true
            && ExpirationConfiguration.isNewConfigEnabled) { // only set "this person is legacy" if new config enabled
            lokiDb.setLastLegacySenderAddress(recipientAddress, messageSender)
        } else if (messageSender == currentLegacyRecipient) {
            lokiDb.setLastLegacySenderAddress(recipientAddress, null)
        }
    }
}

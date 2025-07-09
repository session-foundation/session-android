package org.thoughtcrime.securesms.repository

import android.content.ContentResolver
import app.cash.copper.Query
import app.cash.copper.flow.observeQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.ReadableUserProfile
import network.loki.messenger.libsession_util.util.Contact
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.messages.MarkAsDeletedMessage
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage
import org.session.libsession.messaging.messages.visible.OpenGroupInvitation
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.upsertContact
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.DatabaseContentProviders
import org.thoughtcrime.securesms.database.DraftDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.SessionJobDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.util.observeChanges
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.sequences.filter
import kotlin.sequences.map

interface ConversationRepository {
    fun observeConversationList(approved: Boolean? = null): Flow<List<ThreadRecord>>

    fun getConfigBasedConversations(
        nts: (ReadableUserProfile) -> Boolean = { false },
        contactFilter: (Contact) -> Boolean = { false },
        groupFilter: (GroupInfo.ClosedGroupInfo) -> Boolean = { false },
        legacyFilter: (GroupInfo.LegacyGroupInfo) -> Boolean = { false },
        communityFilter: (GroupInfo.CommunityGroupInfo) -> Boolean = { false }
    ): List<Address>

    fun maybeGetRecipientForThreadId(threadId: Long): Address?
    fun maybeGetBlindedRecipient(address: Address): Address?
    fun changes(threadId: Long): Flow<Query>
    fun saveDraft(threadId: Long, text: String)
    fun getDraft(threadId: Long): String?
    fun clearDrafts(threadId: Long)
    fun inviteContactsToCommunity(threadId: Long, contacts: List<Address>)
    fun setBlocked(recipient: Address, blocked: Boolean)
    fun markAsDeletedLocally(messages: Set<MessageRecord>, displayedMessage: String)
    fun deleteMessages(messages: Set<MessageRecord>, threadId: Long)
    fun deleteAllLocalMessagesInThreadFromSenderOfMessage(messageRecord: MessageRecord)
    fun setApproved(recipient: Address, isApproved: Boolean)
    fun isGroupReadOnly(recipient: Recipient): Boolean
    fun getLastSentMessageID(threadId: Long): Flow<MessageId?>

    suspend fun deleteCommunityMessagesRemotely(threadId: Long, messages: Set<MessageRecord>)
    suspend fun delete1on1MessagesRemotely(
        threadId: Long,
        recipient: Address,
        messages: Set<MessageRecord>
    )
    suspend fun deleteNoteToSelfMessagesRemotely(
        threadId: Long,
        recipient: Address,
        messages: Set<MessageRecord>
    )
    suspend fun deleteLegacyGroupMessagesRemotely(
        recipient: Address,
        messages: Set<MessageRecord>
    )

    suspend fun deleteGroupV2MessagesRemotely(recipient: Address, messages: Set<MessageRecord>)

    suspend fun banUser(threadId: Long, recipient: Address): Result<Unit>
    suspend fun banAndDeleteAll(threadId: Long, recipient: Address): Result<Unit>
    suspend fun deleteThread(threadId: Long): Result<Unit>
    suspend fun deleteMessageRequest(thread: ThreadRecord): Result<Unit>
    suspend fun clearAllMessageRequests(block: Boolean): Result<Unit>
    suspend fun acceptMessageRequest(threadId: Long, recipient: Address): Result<Unit>
    suspend fun declineMessageRequest(threadId: Long, recipient: Address): Result<Unit>
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

@Singleton
class DefaultConversationRepository @Inject constructor(
    private val textSecurePreferences: TextSecurePreferences,
    private val messageDataProvider: MessageDataProvider,
    private val threadDb: ThreadDatabase,
    private val draftDb: DraftDatabase,
    private val lokiThreadDb: LokiThreadDatabase,
    private val smsDb: SmsDatabase,
    private val mmsSmsDb: MmsSmsDatabase,
    private val storage: Storage,
    private val lokiMessageDb: LokiMessageDatabase,
    private val sessionJobDb: SessionJobDatabase,
    private val configFactory: ConfigFactory,
    private val contentResolver: ContentResolver,
    private val groupManager: GroupManagerV2,
    private val clock: SnodeClock,
    private val preferences: TextSecurePreferences,
    private val recipientDatabase: RecipientDatabase,
) : ConversationRepository {

    override fun getConfigBasedConversations(
        nts: (ReadableUserProfile) -> Boolean,
        contactFilter: (Contact) -> Boolean,
        groupFilter: (GroupInfo.ClosedGroupInfo) -> Boolean,
        legacyFilter: (GroupInfo.LegacyGroupInfo) -> Boolean,
        communityFilter: (GroupInfo.CommunityGroupInfo) -> Boolean
    ): List<Address> {
        val (shouldHaveNts, contacts, groups) = configFactory.withUserConfigs { configs ->
            Triple(
                configs.userProfile.getNtsPriority() >= 0 && nts(configs.userProfile),
                configs.contacts.all(),
                configs.userGroups.all(),
            )
        }

        val localNumber = preferences.getLocalNumber()

        val ntsSequence = sequenceOf(
            localNumber
                ?.takeIf { shouldHaveNts }
                ?.let(Address::fromSerialized))
            .filterNotNull()

        val contactsSequence = contacts.asSequence()
            .filter { it.priority >= 0 && contactFilter(it) }
            // Exclude self in the contact if exists
            .filterNot { it.id.equals(localNumber, ignoreCase = true) }
            .map { Address.fromSerialized(it.id) }

        val groupsSequence = groups.asSequence()
            .filterIsInstance<GroupInfo.ClosedGroupInfo>()
            .filter { it.priority >= 0 && groupFilter(it) }
            .map { Address.fromSerialized(it.groupAccountId) }

        val legacyGroupsSequence = groups.asSequence()
            .filterIsInstance<GroupInfo.LegacyGroupInfo>()
            .filter { it.priority >= 0 && legacyFilter(it) }
            .map { Address.fromSerialized(GroupUtil.doubleEncodeGroupID(it.accountId)) }

        val communityGroupsSequence = groups.asSequence()
            .filterIsInstance<GroupInfo.CommunityGroupInfo>()
            .filter(communityFilter)
            .map { Address.fromSerialized(GroupUtil.getEncodedOpenGroupID(it.groupId.toByteArray())) }

        return (ntsSequence + contactsSequence + groupsSequence + legacyGroupsSequence + communityGroupsSequence).toList()
    }

    private val GroupInfo.CommunityGroupInfo.groupId: String
        get() = "${community.baseUrl}.${community.room}"

    private val GroupInfo.ClosedGroupInfo.approved: Boolean
        get() = !invited

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun observeConversationList(approved: Boolean?): Flow<List<ThreadRecord>> {
        val configBasedFlow = configFactory.userConfigsChanged(200)
            .onStart { emit(Unit) }
            .map {
                getConfigBasedConversations(
                    nts = { approved == null || approved },
                    contactFilter = {
                        !it.blocked && (approved == null || it.approved == approved)
                    },
                    groupFilter = {
                        approved == null || it.approved == approved
                    },
                    legacyFilter = { approved != false }, // Legacy groups are always approved
                    communityFilter = { approved != false } // Communities are always approved
                )
            }
            .distinctUntilChanged()

        val blindConvoFlow = (threadDb.updateNotifications
            .debounce(500) as Flow<*>)
            .onStart { emit(Unit) }
            .map { threadDb.getBlindedConversations(approved) }
            .distinctUntilChanged()

        return combine(configBasedFlow, blindConvoFlow) { configBased, blinded ->
            configBased + blinded
        }.flatMapLatest { allAddresses ->
            merge(
                configFactory.configUpdateNotifications,
                recipientDatabase.updateNotifications,
                threadDb.updateNotifications
            ).debounce(500)
                .onStart { emit(Unit) }
                .mapLatest {
                    withContext(Dispatchers.Default) {
                        threadDb.getFilteredConversationList(allAddresses)
                    }
                }
        }
    }

    override fun maybeGetRecipientForThreadId(threadId: Long): Address? {
        return threadDb.getRecipientForThreadId(threadId)
    }

    override fun maybeGetBlindedRecipient(address: Address): Address? {
        if (!address.isCommunityInbox) return null
        return Address.fromSerialized(GroupUtil.getDecodedOpenGroupInboxAccountId(address.toString()))
    }

    override fun changes(threadId: Long): Flow<Query> =
        contentResolver.observeQuery(DatabaseContentProviders.Conversation.getUriForThread(threadId))

    override fun saveDraft(threadId: Long, text: String) {
        if (text.isEmpty()) return
        val drafts = DraftDatabase.Drafts()
        drafts.add(DraftDatabase.Draft(DraftDatabase.Draft.TEXT, text))
        draftDb.insertDrafts(threadId, drafts)
    }

    override fun getDraft(threadId: Long): String? {
        val drafts = draftDb.getDrafts(threadId)
        return drafts.find { it.type == DraftDatabase.Draft.TEXT }?.value
    }

    override fun clearDrafts(threadId: Long) {
        draftDb.clearDrafts(threadId)
    }

    override fun inviteContactsToCommunity(threadId: Long, contacts: List<Address>) {
        val openGroup = lokiThreadDb.getOpenGroupChat(threadId) ?: return
        for (contact in contacts) {
            val message = VisibleMessage()
            message.sentTimestamp = clock.currentTimeMills()
            val openGroupInvitation = OpenGroupInvitation().apply {
                name = openGroup.name
                url = openGroup.joinURL
            }
            message.openGroupInvitation = openGroupInvitation
            val contactThreadId = threadDb.getOrCreateThreadIdFor(contact)
            val expirationConfig = contactThreadId.let(storage::getExpirationConfiguration)
            val expireStartedAt = if (expirationConfig is ExpiryMode.AfterSend) message.sentTimestamp!! else 0
            val outgoingTextMessage = OutgoingTextMessage.fromOpenGroupInvitation(
                openGroupInvitation,
                contact,
                message.sentTimestamp,
                expirationConfig.expiryMillis,
                expireStartedAt
            )

            message.id = MessageId(
                smsDb.insertMessageOutbox(contactThreadId, outgoingTextMessage, false, message.sentTimestamp!!, true),
                false
            )

            MessageSender.send(message, contact)
        }
    }

    override fun isGroupReadOnly(recipient: Recipient): Boolean {
        // We only care about group v2 recipient
        if (!recipient.isGroupV2Recipient) {
            return false
        }

        val groupId = recipient.address.toString()
        return configFactory.withUserConfigs { configs ->
            configs.userGroups.getClosedGroup(groupId)?.let { it.kicked || it.destroyed } == true
        }
    }

    override fun getLastSentMessageID(threadId: Long): Flow<MessageId?> {
        return (contentResolver.observeChanges(DatabaseContentProviders.Conversation.getUriForThread(threadId)) as Flow<*>)
            .onStart { emit(Unit) }
            .map {
                withContext(Dispatchers.Default) {
                    mmsSmsDb.getLastSentMessageID(threadId)
                }
            }
    }

    // This assumes that recipient.isContactRecipient is true
    override fun setBlocked(recipient: Address, blocked: Boolean) {
        if (recipient.isContact) {
            storage.setBlocked(listOf(recipient), blocked)
        }
    }

    /**
     * This will delete these messages from the db
     * Not to be confused with 'marking messages as deleted'
     */
    override fun deleteMessages(messages: Set<MessageRecord>, threadId: Long) {
        // split the messages into mms and sms
        val (mms, sms) = messages.partition { it.isMms }

        if(mms.isNotEmpty()){
            messageDataProvider.deleteMessages(mms.map { it.id }, threadId, isSms = false)
        }

        if(sms.isNotEmpty()){
            messageDataProvider.deleteMessages(sms.map { it.id }, threadId, isSms = true)
        }
    }

    /**
     * This will mark the messages as deleted.
     * They won't be removed from the db but instead will appear as a special type
     * of message that says something like "This message was deleted"
     */
    override fun markAsDeletedLocally(messages: Set<MessageRecord>, displayedMessage: String) {
        // split the messages into mms and sms
        val (mms, sms) = messages.partition { it.isMms }

        if(mms.isNotEmpty()){
            messageDataProvider.markMessagesAsDeleted(
                mms.map { MarkAsDeletedMessage(
                    messageId = it.messageId,
                    isOutgoing = it.isOutgoing
                ) },
                displayedMessage = displayedMessage
            )

            // delete reactions
            storage.deleteReactions(messageIds = mms.map { it.id }, mms = true)
        }

        if(sms.isNotEmpty()){
            messageDataProvider.markMessagesAsDeleted(
                sms.map { MarkAsDeletedMessage(
                    messageId = it.messageId,
                    isOutgoing = it.isOutgoing
                ) },
                displayedMessage = displayedMessage
            )

            // delete reactions
            storage.deleteReactions(messageIds = sms.map { it.id }, mms = false)
        }
    }

    override fun deleteAllLocalMessagesInThreadFromSenderOfMessage(messageRecord: MessageRecord) {
        val threadId = messageRecord.threadId
        val senderId = messageRecord.recipient.address.contactIdentifier()
        val messageRecordsToRemoveFromLocalStorage = mmsSmsDb.getAllMessageRecordsFromSenderInThread(threadId, senderId)
        for (message in messageRecordsToRemoveFromLocalStorage) {
            messageDataProvider.deleteMessage(messageId = message.messageId)
        }
    }

    override fun setApproved(recipient: Address, isApproved: Boolean) {
        if (IdPrefix.fromValue(recipient.address) == IdPrefix.STANDARD) {
            configFactory.withMutableUserConfigs { configs ->
                configs.contacts.upsertContact(recipient.address) {
                    approved = isApproved
                }
            }
        } else {
            // Can not approve anything that is not a standard contact
        }
    }

    override suspend fun deleteCommunityMessagesRemotely(
        threadId: Long,
        messages: Set<MessageRecord>
    ) {
        val community = checkNotNull(lokiThreadDb.getOpenGroupChat(threadId)) { "Not a community" }

        messages.forEach { message ->
            lokiMessageDb.getServerID(message.messageId)?.let { messageServerID ->
                OpenGroupApi.deleteMessage(messageServerID, community.room, community.server).await()
            }
        }
    }

    override suspend fun delete1on1MessagesRemotely(
        threadId: Long,
        recipient: Address,
        messages: Set<MessageRecord>
    ) {
        // delete the messages remotely
        val userAddress: Address? =  textSecurePreferences.getLocalNumber()?.let { Address.fromSerialized(it) }
        val userAuth = requireNotNull(storage.userAuth) {
            "User auth is required to delete messages remotely"
        }

        messages.forEach { message ->
            // delete from swarm
            messageDataProvider.getServerHashForMessage(message.messageId)
                ?.let { serverHash ->
                    SnodeAPI.deleteMessage(recipient.address, userAuth, listOf(serverHash))
                }

            // send an UnsendRequest to user's swarm
            buildUnsendRequest(message).let { unsendRequest ->
                userAddress?.let { MessageSender.send(unsendRequest, it) }
            }

            // send an UnsendRequest to recipient's swarm
            buildUnsendRequest(message).let { unsendRequest ->
                MessageSender.send(unsendRequest, recipient)
            }
        }
    }

    override suspend fun deleteLegacyGroupMessagesRemotely(
        recipient: Address,
        messages: Set<MessageRecord>
    ) {
        if (recipient.isLegacyGroup) {
            messages.forEach { message ->
                // send an UnsendRequest to group's swarm
                buildUnsendRequest(message).let { unsendRequest ->
                    MessageSender.send(unsendRequest, recipient)
                }
            }
        }
    }

    override suspend fun deleteGroupV2MessagesRemotely(
        recipient: Address,
        messages: Set<MessageRecord>
    ) {
        require(recipient.isGroupV2) { "Recipient is not a group v2 recipient" }

        val groupId = AccountId(recipient.address)
        val hashes = messages.mapNotNullTo(mutableSetOf()) { msg ->
            messageDataProvider.getServerHashForMessage(msg.messageId)
        }

        groupManager.requestMessageDeletion(groupId, hashes)
    }

    override suspend fun deleteNoteToSelfMessagesRemotely(
        threadId: Long,
        recipient: Address,
        messages: Set<MessageRecord>
    ) {
        // delete the messages remotely
        val userAddress: Address? =  textSecurePreferences.getLocalNumber()?.let { Address.fromSerialized(it) }
        val userAuth = requireNotNull(storage.userAuth) {
            "User auth is required to delete messages remotely"
        }

        messages.forEach { message ->
            // delete from swarm
            messageDataProvider.getServerHashForMessage(message.messageId)
                ?.let { serverHash ->
                    SnodeAPI.deleteMessage(recipient.address, userAuth, listOf(serverHash))
                }

            // send an UnsendRequest to user's swarm
            buildUnsendRequest(message).let { unsendRequest ->
                userAddress?.let { MessageSender.send(unsendRequest, it) }
            }
        }
    }

    private fun buildUnsendRequest(message: MessageRecord): UnsendRequest {
        return UnsendRequest(
            author = message.takeUnless { it.isOutgoing }?.run { individualRecipient.address.contactIdentifier() } ?: textSecurePreferences.getLocalNumber(),
            timestamp = message.timestamp
        )
    }

    override suspend fun banUser(threadId: Long, recipient: Address): Result<Unit> = runCatching {
        val openGroup = lokiThreadDb.getOpenGroupChat(threadId)!!
        OpenGroupApi.ban(recipient.toString(), openGroup.room, openGroup.server).await()
    }

    override suspend fun banAndDeleteAll(threadId: Long, recipient: Address) = runCatching {
        // Note: This accountId could be the blinded Id
        val openGroup = lokiThreadDb.getOpenGroupChat(threadId)!!

        OpenGroupApi.banAndDeleteAll(recipient.toString(), openGroup.room, openGroup.server).await()
    }

    override suspend fun deleteThread(threadId: Long) = runCatching {
        withContext(Dispatchers.Default) {
            sessionJobDb.cancelPendingMessageSendJobs(threadId)
            storage.deleteConversation(threadId)
        }
    }

    override suspend fun deleteMessageRequest(thread: ThreadRecord)
        = declineMessageRequest(thread.threadId, thread.recipient.address)

    override suspend fun clearAllMessageRequests(block: Boolean) = runCatching {
        observeConversationList(approved = false)
            .first()
            .forEach { record ->
                deleteMessageRequest(record)
                val recipient = record.recipient
                if (block && !recipient.isGroupV2Recipient) {
                    setBlocked(recipient.address, true)
                }
            }
    }

    override suspend fun clearAllMessages(threadId: Long, groupId: AccountId?): Int {
        return withContext(Dispatchers.Default) {
            // delete data locally
            val deletedHashes = storage.clearAllMessages(threadId)
            Log.i("", "Cleared messages with hashes: $deletedHashes")

            // if required, also sync groupV2 data
            if (groupId != null) {
                groupManager.clearAllMessagesForEveryone(groupId, deletedHashes)
            }

            deletedHashes.size
        }
    }

    override suspend fun acceptMessageRequest(threadId: Long, recipient: Address) = runCatching {
        withContext(Dispatchers.Default) {
            setApproved(recipient, true)
            if (recipient.isGroupV2) {
                groupManager.respondToInvitation(
                    AccountId(recipient.toString()),
                    approved = true
                )
            } else {
                val message = MessageRequestResponse(true)

                MessageSender.send(message = message, address = recipient)

                // add a control message for our user
                storage.insertMessageRequestResponseFromYou(threadId)
            }

            threadDb.setHasSent(threadId, true)
        }
    }

    override suspend fun declineMessageRequest(threadId: Long, recipient: Address): Result<Unit> = runCatching {
        withContext(Dispatchers.Default) {
            sessionJobDb.cancelPendingMessageSendJobs(threadId)
            if (recipient.isGroupV2) {
                groupManager.respondToInvitation(
                    AccountId(recipient.toString()),
                    approved = false
                )
            } else {
                storage.deleteContactAndSyncConfig(recipient.toString())
            }
        }
    }

    override fun hasReceived(threadId: Long): Boolean {
        val cursor = mmsSmsDb.getConversation(threadId, true)
        mmsSmsDb.readerFor(cursor).use { reader ->
            while (reader.next != null) {
                if (!reader.current.isOutgoing) { return true }
            }
        }
        return false
    }

    // Only call this with a closed group thread ID
    override fun getInvitingAdmin(threadId: Long): Address? {
        return lokiMessageDb.groupInviteReferrer(threadId)?.let(Address::fromSerialized)
    }
}
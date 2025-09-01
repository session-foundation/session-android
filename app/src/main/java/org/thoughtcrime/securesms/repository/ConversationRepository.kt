package org.thoughtcrime.securesms.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
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
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.isGroupV2
import org.session.libsession.utilities.isLegacyGroup
import org.session.libsession.utilities.isStandard
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.upsertContact
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.DraftDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.util.castAwayType
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

interface ConversationRepository {
    fun observeConversationList(): Flow<List<ThreadRecord>>

    /**
     * Returns a list of threads that are visible to the user. Note that this
     * list includes both approved and unapproved threads.
     */

    fun getConversationList(): List<ThreadRecord>


    val conversationListAddressesFlow: StateFlow<Set<Address.Conversable>>

    fun saveDraft(threadId: Long, text: String)
    fun getDraft(threadId: Long): String?
    fun clearDrafts(threadId: Long)
    fun inviteContactsToCommunity(threadId: Long, contacts: Collection<Address>)
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
    private val configFactory: ConfigFactory,
    private val groupManager: GroupManagerV2,
    private val clock: SnodeClock,
    private val recipientDatabase: RecipientSettingsDatabase,
    private val recipientRepository: RecipientRepository,
    @param:ManagerScope private val scope: CoroutineScope,
) : ConversationRepository {

    override val conversationListAddressesFlow = configFactory
        .userConfigsChanged(EnumSet.of(
            UserConfigType.CONTACTS,
            UserConfigType.USER_PROFILE,
            UserConfigType.USER_GROUPS
        ))
        .castAwayType()
        .onStart {
            // Only start when we have a local number
            textSecurePreferences.watchLocalNumber().filterNotNull().first()

            emit(Unit)
        }
        .map { getConversationListAddresses() }
        .stateIn(scope, SharingStarted.Eagerly, getConversationListAddresses())

    private fun getConversationListAddresses() = buildSet {
        val myAddress = Address.Standard(AccountId(textSecurePreferences.getLocalNumber() ?: return@buildSet ))

        // Always have NTS - we should only "hide" them on home screen - the convo should never be deleted
        add(myAddress)

        configFactory.withUserConfigs { configs ->
            // Contacts
            for (contact in configs.contacts.all()) {
                if (contact.priority >= 0 && (!contact.blocked || contact.approved)) {
                    add(Address.Standard(AccountId(contact.id)))
                }
            }

            // Blinded Contacts
            for (blindedContact in configs.contacts.allBlinded()) {
                if (blindedContact.priority >= 0) {
                    add(Address.CommunityBlindedId(
                        serverUrl = blindedContact.communityServer,
                        blindedId = Address.Blinded(AccountId(blindedContact.id))
                    ))
                }
            }

            // Groups
            for (group in configs.userGroups.all()) {
                when (group) {
                    is GroupInfo.ClosedGroupInfo -> {
                        add(Address.Group(AccountId(group.groupAccountId)))
                    }

                    is GroupInfo.LegacyGroupInfo -> {
                        add(Address.LegacyGroup(group.accountId))
                    }

                    is GroupInfo.CommunityGroupInfo -> {
                        add(Address.Community(
                            serverUrl = group.community.baseUrl,
                            room = group.community.room
                        ))
                    }
                }
            }
        }
    }


    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun observeConversationList(): Flow<List<ThreadRecord>> {
        return conversationListAddressesFlow
            .flatMapLatest { allAddresses ->
                merge(
                    configFactory.configUpdateNotifications,
                    recipientDatabase.changeNotification,
                    threadDb.updateNotifications
                ).debounce(500)
                    .onStart { emit(Unit) }
                    .mapLatest {
                        withContext(Dispatchers.Default) {
                            threadDb.getThreads(allAddresses)
                        }
                    }
            }
    }

    override fun getConversationList(): List<ThreadRecord> {
        return threadDb.getThreads(getConversationListAddresses())
    }

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

    override fun inviteContactsToCommunity(threadId: Long, contacts: Collection<Address>) {
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
            val expirationConfig = recipientRepository.getRecipientSync(contact).expiryMode
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
        return (threadDb.updateNotifications.filter { it == threadId } as Flow<*>)
            .onStart { emit(Unit) }
            .map {
                withContext(Dispatchers.Default) {
                    mmsSmsDb.getLastSentMessageID(threadId)
                }
            }
    }

    // This assumes that recipient.isContactRecipient is true
    override fun setBlocked(recipient: Address, blocked: Boolean) {
        if (recipient.isStandard) {
            storage.setBlocked(listOf(recipient), blocked)
        }
    }

    /**
     * This will delete these messages from the db
     * Not to be confused with 'marking messages as deleted'
     */
    override fun deleteMessages(messages: Set<MessageRecord>) {
        // split the messages into mms and sms
        val (mms, sms) = messages.partition { it.isMms }

        if(mms.isNotEmpty()){
            messageDataProvider.deleteMessages(mms.map { it.id }, isSms = false)
        }

        if(sms.isNotEmpty()){
            messageDataProvider.deleteMessages(sms.map { it.id }, isSms = true)
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
        val senderId = messageRecord.recipient.address.address
        val messageRecordsToRemoveFromLocalStorage = mmsSmsDb.getAllMessageRecordsFromSenderInThread(threadId, senderId)
        for (message in messageRecordsToRemoveFromLocalStorage) {
            messageDataProvider.deleteMessage(messageId = message.messageId)
        }
    }

    override suspend fun deleteCommunityMessagesRemotely(
        community: Address.Community,
        messages: Set<MessageRecord>
    ) {
        messages.forEach { message ->
            lokiMessageDb.getServerID(message.messageId)?.let { messageServerID ->
                OpenGroupApi.deleteMessage(messageServerID, community.room, community.serverUrl).await()
            }
        }
    }

    override suspend fun delete1on1MessagesRemotely(
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
            author = message.takeUnless { it.isOutgoing }?.run { individualRecipient.address.address } ?: textSecurePreferences.getLocalNumber(),
            timestamp = message.timestamp
        )
    }

    override suspend fun banUser(community: Address.Community, userId: AccountId): Result<Unit> = runCatching {
        OpenGroupApi.ban(
            publicKey = userId.hexString,
            room = community.room,
            server = community.serverUrl,
        ).await()
    }

    override suspend fun banAndDeleteAll(community: Address.Community, userId: AccountId) = runCatching {
        // Note: This accountId could be the blinded Id
        OpenGroupApi.banAndDeleteAll(
            publicKey = userId.hexString,
            room = community.room,
            server = community.serverUrl
        ).await()
    }

    override suspend fun deleteMessageRequest(thread: ThreadRecord): Result<Unit> {
        val address = thread.recipient.address as? Address.Conversable ?: return Result.success(Unit)

        return declineMessageRequest(
            address
        )
    }

    override suspend fun clearAllMessageRequests() = runCatching {

        configFactory.withMutableUserConfigs { configs ->
            // Go through all contacts
            configs.contacts.all()
                .asSequence()
                .filter { !it.approved }
                .forEach {
                    configs.contacts.erase(it.id)
                }


            // Go through all invited groups
            configs.userGroups.allClosedGroupInfo()
                .asSequence()
                .filter { it.invited }
                .forEach { g ->
                    configs.userGroups.eraseClosedGroup(g.groupAccountId)
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

    override suspend fun acceptMessageRequest(recipient: Address.Conversable) = runCatching {
        when (recipient) {
            is Address.Standard -> {
                configFactory.withMutableUserConfigs { configs ->
                    configs.contacts.upsertContact(recipient) {
                        approved = true
                    }
                }

                withContext(Dispatchers.Default) {
                    MessageSender.send(message = MessageRequestResponse(true), address = recipient)

                    // add a control message for our user
                    storage.insertMessageRequestResponseFromYou(threadDb.getOrCreateThreadIdFor(recipient))
                }
            }

            is Address.Group -> {
                groupManager.respondToInvitation(
                    recipient.accountId,
                    approved = true
                )
            }

            is Address.Community,
            is Address.CommunityBlindedId,
            is Address.LegacyGroup -> {
                // These addresses are not supported for message requests
            }
        }

        Unit
    }

    override suspend fun declineMessageRequest(recipient: Address.Conversable): Result<Unit> = runCatching {
        when (recipient) {
            is Address.Standard -> {
                configFactory.removeContactOrBlindedContact(recipient)
            }

            is Address.Group -> {
                groupManager.respondToInvitation(
                    recipient.accountId,
                    approved = false
                )
            }

            is Address.Community,
            is Address.CommunityBlindedId,
            is Address.LegacyGroup -> {
                // These addresses are not supported for message requests
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
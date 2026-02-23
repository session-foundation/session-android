package org.session.libsession.messaging.sending_receiving

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.protocol.DecodedPro
import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import network.loki.messenger.libsession_util.util.KeyPair
import okio.withLock
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.Message.Companion.senderOrSync
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.messages.control.DataExtractionNotification
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.messages.control.TypingIndicator
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.GroupUtil.doubleEncodeGroupID
import org.session.libsession.utilities.MessageExpirationManagerProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TypingIndicatorsProtocol
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.recipients.MessageType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.getType
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.session.protos.SessionProtos
import org.thoughtcrime.securesms.api.snode.DeleteMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.api.swarm.execute
import org.thoughtcrime.securesms.database.BlindMappingRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.sskenvironment.ReadReceiptManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ReceivedMessageProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val recipientRepository: RecipientRepository,
    private val storage: Storage,
    private val configFactory: ConfigFactoryProtocol,
    private val threadDatabase: ThreadDatabase,
    private val readReceiptManager: Provider<ReadReceiptManager>,
    private val typingIndicators: Provider<TypingIndicatorsProtocol>,
    private val prefs: TextSecurePreferences,
    private val groupMessageHandler: Provider<GroupMessageHandler>,
    private val messageExpirationManager: Provider<MessageExpirationManagerProtocol>,
    private val messageDataProvider: MessageDataProvider,
    @param:ManagerScope private val scope: CoroutineScope,
    private val notificationManager: MessageNotifier,
    private val messageRequestResponseHandler: Provider<MessageRequestResponseHandler>,
    private val visibleMessageHandler: Provider<VisibleMessageHandler>,
    private val blindMappingRepository: BlindMappingRepository,
    private val messageParser: MessageParser,
    private val swarmApiExecutor: SwarmApiExecutor,
    private val deleteMessageApiFactory: DeleteMessageApi.Factory
) {
    private val threadMutexes = ConcurrentHashMap<Address.Conversable, ReentrantLock>()

    private inline fun <T> withThreadLock(
        threadAddress: Address.Conversable,
        block: () -> T
    ) {
        threadMutexes.getOrPut(threadAddress) { ReentrantLock() }.withLock {
            block()
        }
    }


    /**
     * Start a message processing session, ensuring that thread updates and notifications are handled
     * once the whole processing is complete.
     *
     * Note: the context passed to the block is not thread-safe, so it should not be shared between threads.
     */
    fun <T> startProcessing(debugName: String, block: (MessageProcessingContext) -> T): T {
        val context = MessageProcessingContext()
        val start = System.currentTimeMillis()
        try {
            return block(context)
        } finally {
            for ((threadAddress, threadId) in context.threadIDs) {
                storage.updateConversationLastSeenIfNeeded(
                    threadAddress = threadAddress,
                    context.maxOutgoingMessageTimestamp,
                )

                notificationManager.updateNotification(this.context, threadId)
                threadDatabase.notifyThreadUpdated(threadId)
            }

            // Handle pending community reactions
            context.pendingCommunityReactions?.let { reactions ->
                storage.addReactions(reactions, replaceAll = true, notifyUnread = false)
                reactions.clear()
            }

            Log.d(TAG, "Processed messages for $debugName in ${System.currentTimeMillis() - start}ms")
        }
    }

    fun processSwarmMessage(
        context: MessageProcessingContext,
        threadAddress: Address.Conversable,
        message: Message,
        proto: SessionProtos.Content,
        pro: DecodedPro?,
    ) = withThreadLock(threadAddress) {
        // The logic to check if the message should be discarded due to being from a hidden contact.
        if (threadAddress is Address.Standard &&
            message.sentTimestamp != null &&
            shouldDiscardForHiddenContact(
                ctx = context,
                messageTimestamp = message.sentTimestamp!!,
                threadAddress = threadAddress
            )
        ) {
            log { "Dropping message from hidden contact ${threadAddress.debugString}" }
            return@withThreadLock
        }

        // Get or create thread ID, if we aren't allowed to create it, and it doesn't exist, drop the message
        val threadId = context.threadIDs[threadAddress] ?: if (shouldCreateThread(message)) {
            threadDatabase.getOrCreateThreadIdFor(threadAddress)
                .also { context.threadIDs[threadAddress] = it }
        } else {
            storage.getThreadId(threadAddress)
                .also { id ->
                    if (id == null) {
                        log { "Dropping message for non-existing thread ${threadAddress.debugString}" }
                        return@withThreadLock
                    } else {
                        context.threadIDs[threadAddress] = id
                    }
                }
        }

        when (message) {
            is ReadReceipt -> handleReadReceipt(message)
            is TypingIndicator -> handleTypingIndicator(message)
            is GroupUpdated -> groupMessageHandler.get().handleGroupUpdated(
                message = message,
                groupId = (threadAddress as? Address.Group)?.accountId,
                proto = proto,
                pro = pro,
            )

            is ExpirationTimerUpdate -> {
                // For groupsv2, there are dedicated mechanisms for handling expiration timers, and
                // we want to avoid the 1-to-1 message format which is unauthenticated in a group settings.
                if (threadAddress is Address.Group) {
                    Log.d("MessageReceiver", "Ignoring expiration timer update for closed group")
                } // also ignore it for communities since they do not support disappearing messages
                else if (threadAddress is Address.Community) {
                    Log.d("MessageReceiver", "Ignoring expiration timer update for communities")
                } else {
                    handleExpirationTimerUpdate(message)
                }
            }

            is DataExtractionNotification -> handleDataExtractionNotification(message)
            is UnsendRequest -> handleUnsendRequest(message)
            is MessageRequestResponse -> messageRequestResponseHandler.get()
                .handleExplicitRequestResponseMessage(context, message, proto, pro)

            is VisibleMessage -> {
                if (message.isSenderSelf &&
                    message.sentTimestamp != null &&
                    message.sentTimestamp!! > context.maxOutgoingMessageTimestamp
                ) {
                    context.maxOutgoingMessageTimestamp = message.sentTimestamp!!
                }

                threadId?.let {
                    visibleMessageHandler.get().handleVisibleMessage(
                        ctx = context,
                        message = message,
                        threadId = it,
                        threadAddress = threadAddress,
                        proto = proto,
                        runThreadUpdate = false,
                        runProfileUpdate = true,
                        pro = pro,
                    )
                }
            }

            is CallMessage -> handleCallMessage(message)
        }

    }

    fun processCommunityInboxMessage(
        context: MessageProcessingContext,
        communityServerUrl: String,
        communityServerPubKeyHex: String,
        message: OpenGroupApi.DirectMessage
    ) {
        val parseResult = messageParser.parseCommunityDirectMessage(
            msg = message,
            currentUserId = context.currentUserId,
            currentUserEd25519PrivKey = context.currentUserEd25519KeyPair.secretKey.data,
            currentUserBlindedIDs = context.getCurrentUserBlindedIDsByServer(communityServerUrl),
            communityServerPubKeyHex = communityServerPubKeyHex,
        )

        val threadAddress = parseResult.message.senderOrSync.toAddress() as Address.Conversable

        withThreadLock(threadAddress) {
            processSwarmMessage(
                context = context,
                threadAddress = threadAddress,
                message = parseResult.message,
                proto = parseResult.proto,
                pro = parseResult.pro
            )
        }
    }

    fun processCommunityOutboxMessage(
        context: MessageProcessingContext,
        communityServerUrl: String,
        communityServerPubKeyHex: String,
        msg: OpenGroupApi.DirectMessage
    ) {
        val parseResult = messageParser.parseCommunityDirectMessage(
            msg = msg,
            currentUserId = context.currentUserId,
            currentUserEd25519PrivKey = context.currentUserEd25519KeyPair.secretKey.data,
            currentUserBlindedIDs = context.getCurrentUserBlindedIDsByServer(communityServerUrl),
            communityServerPubKeyHex = communityServerPubKeyHex,
        )

        val threadAddress = Address.CommunityBlindedId(
            serverUrl = communityServerUrl,
            blindedId = Address.Blinded(AccountId(msg.recipient))
        )

        withThreadLock(threadAddress) {
            processSwarmMessage(
                context = context,
                threadAddress = threadAddress,
                message = parseResult.message,
                proto = parseResult.proto,
                pro = parseResult.pro
            )
        }
    }

    fun processCommunityMessage(
        context: MessageProcessingContext,
        threadAddress: Address.Community,
        message: OpenGroupApi.Message,
    ) = withThreadLock(threadAddress) {
        var messageId = messageParser.parseCommunityMessage(
            msg = message,
            currentUserId = context.currentUserId,
            currentUserBlindedIDs = context.getCurrentUserBlindedIDsByThread(threadAddress)
        )?.let { parseResult ->
            processSwarmMessage(
                context = context,
                threadAddress = threadAddress,
                message = parseResult.message,
                proto = parseResult.proto,
                pro = parseResult.pro
            )

            parseResult.message.id
        }

        // For community, we have a different way of handling reaction, this is outside of
        // the normal enveloped message (even though enveloped message can also contain reaction,
        // it's not used by anyone at the moment).
        if (messageId == null) {
            Log.d(TAG, "Handling reactions only message for community ${threadAddress.debugString}")
            messageId = requireNotNull(
                messageDataProvider.getMessageID(
                serverId = message.id,
                threadId = requireNotNull(storage.getThreadId(threadAddress)) {
                    "No thread ID for community ${threadAddress.debugString}"
                }
            )) {
                "No message persisted for community message ${message.id}"
            }
        }

        val messageServerId = message.id.toString()
        val reactions = mutableListOf<ReactionRecord>()

        for ((emoji, reaction) in message.reactions.orEmpty()) {
            // We only really want up to 5 reactors per reaction to avoid excessive database load
            // Among the 5 reactors, we must include ourselves if we reacted to this message
            val otherReactorsToAdd = if (reaction.you) {
                reactions += ReactionRecord(
                    messageId = messageId,
                    author = context.currentUserPublicKey,
                    emoji = emoji,
                    serverId = messageServerId,
                    count = reaction.count,
                    sortId = 0,
                )

                val myBlindedIDs = context.getCurrentUserBlindedIDsByThread(threadAddress)

                reaction.reactors
                    .asSequence()
                    .filterNot { reactor -> reactor == context.currentUserPublicKey || myBlindedIDs.any { it.hexString == reactor } }
                    .take(4)
            } else {
                reaction.reactors
                    .asSequence()
                    .take(5)
            }


            for (reactor in otherReactorsToAdd) {
                reactions += ReactionRecord(
                    messageId = messageId,
                    author = reactor,
                    emoji = emoji,
                    serverId = messageServerId,
                    count = reaction.count,
                    sortId = reaction.index,
                )
            }
        }

        context.setCommunityMessageReactions(messageId, reactions)
    }

    private fun handleReadReceipt(message: ReadReceipt) {
        readReceiptManager.get().processReadReceipts(
            message.sender!!,
            message.timestamps!!,
            message.receivedTimestamp!!
        )
    }

    private fun handleTypingIndicator(message: TypingIndicator) {
        when (message.kind!!) {
            TypingIndicator.Kind.STARTED -> showTypingIndicatorIfNeeded(message.sender!!)
            TypingIndicator.Kind.STOPPED -> hideTypingIndicatorIfNeeded(message.sender!!)
        }
    }

    private fun showTypingIndicatorIfNeeded(senderPublicKey: String) {
        // We don't want to show other people's indicators if the toggle is off
        if (!prefs.isTypingIndicatorsEnabled()) return

        val address = Address.fromSerialized(senderPublicKey)
        val threadID = storage.getThreadId(address) ?: return
        typingIndicators.get().didReceiveTypingStartedMessage(threadID, address, 1)
    }

    private fun hideTypingIndicatorIfNeeded(senderPublicKey: String) {
        val address = Address.fromSerialized(senderPublicKey)
        val threadID = storage.getThreadId(address) ?: return
        typingIndicators.get().didReceiveTypingStoppedMessage(threadID, address, 1, false)
    }


    /**
     * Return true if this message should result in the creation of a thread.
     */
    private fun shouldCreateThread(message: Message): Boolean {
        return message is VisibleMessage || message is GroupUpdated
    }

    private fun handleExpirationTimerUpdate(message: ExpirationTimerUpdate) {
        messageExpirationManager.get().run {
            insertExpirationTimerMessage(message)
            onMessageReceived(message)
        }
    }

    private fun handleDataExtractionNotification(message: DataExtractionNotification) {
        // We don't handle data extraction messages for groups (they shouldn't be sent, but just in case we filter them here too)
        if (message.groupPublicKey != null) return
        val senderPublicKey = message.sender!!

        val notification: DataExtractionNotificationInfoMessage = when (message.kind) {
            is DataExtractionNotification.Kind.MediaSaved -> DataExtractionNotificationInfoMessage(
                DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED
            )

            else -> return
        }
        storage.insertDataExtractionNotificationMessage(
            senderPublicKey,
            notification,
            message.sentTimestamp!!
        )
    }

    fun handleUnsendRequest(message: UnsendRequest): MessageId? {
        val userPublicKey = storage.getUserPublicKey()
        val userAuth = storage.userAuth ?: return null
        val isLegacyGroupAdmin: Boolean = message.groupPublicKey?.let { key ->
            var admin = false
            val groupID = doubleEncodeGroupID(key)
            val group = storage.getGroup(groupID)
            if (group != null) {
                admin = group.admins.map { it.toString() }.contains(message.sender)
            }
            admin
        } ?: false

        // First we need to determine the validity of the UnsendRequest
        // It is valid if:
        val requestIsValid =
            message.sender == message.author || //  the sender is the author of the message
                    message.author == userPublicKey || //  the sender is the current user
                    isLegacyGroupAdmin // sender is an admin of legacy group

        if (!requestIsValid) {
            return null
        }

        val timestamp = message.timestamp ?: return null
        val author = message.author ?: return null
        val messageToDelete = storage.getMessageByTimestamp(timestamp, author, false) ?: return null
        val messageIdToDelete = messageToDelete.messageId
        val messageType = messageToDelete.individualRecipient?.getType()

        // send a /delete rquest for 1on1 messages
        if (messageType == MessageType.ONE_ON_ONE) {
            messageDataProvider.getServerHashForMessage(messageIdToDelete)?.let { serverHash ->
                scope.launch { // using scope as we are slowly migrating to coroutines but we can't migrate everything at once
                    try {
                        swarmApiExecutor.execute(
                            SwarmApiRequest(
                                swarmPubKeyHex = userAuth.accountId.hexString,
                                api = deleteMessageApiFactory.create(
                                    messageHashes = listOf(serverHash),
                                    swarmAuth = userAuth
                                )
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("Loki", "Failed to delete message", e)
                    }
                }
            }
        }

        // the message is marked as deleted locally
        // except for 'note to self' where the message is completely deleted
        if (messageType == MessageType.NOTE_TO_SELF) {
            messageDataProvider.deleteMessage(messageIdToDelete)
        } else {
            messageDataProvider.markMessageAsDeleted(
                messageIdToDelete,
                displayedMessage = context.getString(R.string.deleteMessageDeletedGlobally)
            )
        }

        // delete reactions
        storage.deleteReactions(messageToDelete.messageId)

        // update notification
        if (!messageToDelete.isOutgoing) {
            notificationManager.updateNotification(context)
        }

        return messageIdToDelete
    }

    private fun handleCallMessage(message: CallMessage) {
        // TODO: refactor this out to persistence, just to help debug the flow and send/receive in synchronous testing
        WebRtcUtils.SIGNAL_QUEUE.trySend(message)
    }


    /**
     * Return true if the contact is marked as hidden for given message timestamp.
     */
    private fun shouldDiscardForHiddenContact(
        ctx: MessageProcessingContext,
        messageTimestamp: Long,
        threadAddress: Address.Standard
    ): Boolean {
        val hidden = configFactory.withUserConfigs { configs ->
            configs.contacts.get(threadAddress.address)?.priority == PRIORITY_HIDDEN
        }

        return hidden &&
                // the message's sentTimestamp is earlier than the sentTimestamp of the last config
                messageTimestamp < ctx.contactConfigTimestamp
    }

    /**
     * A context object for processing received messages. This object is mostly used to store
     * expensive data that are only valid for the duration of a processing session.
     *
     * It also tracks some deferred updates that should be applied once processing is complete,
     * such as thread updates, reactions, and notifications.
     */
    inner class MessageProcessingContext {
        private var recipients: HashMap<Address.Conversable, Recipient>? = null
        val threadIDs: HashMap<Address.Conversable, Long> = hashMapOf()
        private var currentUserBlindedKeysByCommunityServer: HashMap<String, List<AccountId>>? = null
        val currentUserId: AccountId = AccountId(requireNotNull(storage.getUserPublicKey()) {
            "No current user available"
        })

        var maxOutgoingMessageTimestamp: Long = 0L

        val currentUserEd25519KeyPair: KeyPair by lazy {
            requireNotNull(storage.getUserED25519KeyPair()) {
                "No current user ED25519 key pair available"
            }
        }

        val currentUserPublicKey: String get() = currentUserId.hexString


        val contactConfigTimestamp: Long by lazy {
            configFactory.getConfigTimestamp(UserConfigType.CONTACTS, currentUserPublicKey)
        }

        private var blindIDMappingCache: HashMap<Address.Standard, List<Pair<BaseCommunityInfo, Address.Blinded>>>? =
            null


        var pendingCommunityReactions: HashMap<MessageId, List<ReactionRecord>>? = null
            private set


        fun getBlindIDMapping(address: Address.Standard): List<Pair<BaseCommunityInfo, Address.Blinded>> {
            val cache = blindIDMappingCache
                ?: hashMapOf<Address.Standard, List<Pair<BaseCommunityInfo, Address.Blinded>>>().also {
                    blindIDMappingCache = it
                }

            return cache.getOrPut(address) {
                blindMappingRepository.calculateReverseMappings(address)
            }
        }


        fun getThreadRecipient(threadAddress: Address.Conversable): Recipient {
            val cache = recipients ?: hashMapOf<Address.Conversable, Recipient>().also {
                recipients = it
            }

            return cache.getOrPut(threadAddress) {
                recipientRepository.getRecipientSync(threadAddress)
            }
        }

        fun getCurrentUserBlindedIDsByServer(serverUrl: String): List<AccountId> {
            val serverPubKey = requireNotNull(storage.getOpenGroupPublicKey(serverUrl)) {
                "No open group public key found"
            }

            val cache =
                currentUserBlindedKeysByCommunityServer ?: hashMapOf<String, List<AccountId>>().also {
                    currentUserBlindedKeysByCommunityServer = it
                }

            return cache.getOrPut(serverUrl) {
                BlindKeyAPI.blind15Ids(
                    sessionId = currentUserPublicKey,
                    serverPubKey = serverPubKey
                ).map(::AccountId) + AccountId(
                    BlindKeyAPI.blind25Id(
                        sessionId = currentUserPublicKey,
                        serverPubKey = serverPubKey
                    )
                )
            }
        }


        fun getCurrentUserBlindedIDsByThread(address: Address.Conversable): List<AccountId> {
            if (address !is Address.Community) return emptyList()
            return getCurrentUserBlindedIDsByServer(address.serverUrl)
        }


        fun setCommunityMessageReactions(messageId: MessageId, reactions: List<ReactionRecord>) {
            val reactionsMap = pendingCommunityReactions
                ?: hashMapOf<MessageId, List<ReactionRecord>>().also {
                    pendingCommunityReactions = it
                }

            reactionsMap[messageId] = reactions
        }
    }

    companion object {
        private const val TAG = "ReceivedMessageProcessor"

        private const val DEBUG_MESSAGE_PROCESSING = true

        private inline fun log(message: () -> String) {
            if (DEBUG_MESSAGE_PROCESSING) {
                Log.d(TAG, message())
            }
        }
    }
}
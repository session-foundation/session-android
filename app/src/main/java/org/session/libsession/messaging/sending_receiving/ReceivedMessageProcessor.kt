package org.session.libsession.messaging.sending_receiving

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okio.withLock
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.messages.control.DataExtractionNotification
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.messages.control.TypingIndicator
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.GroupUtil.doubleEncodeGroupID
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.recipients.MessageType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.getType
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageId
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
    private val messageParser: MessageParser,
    private val storage: Storage,
    private val configFactory: ConfigFactoryProtocol,
    private val threadDatabase: ThreadDatabase,
    private val readReceiptManager: Provider<ReadReceiptManager>,
    private val typingIndicators: Provider<SSKEnvironment.TypingIndicatorsProtocol>,
    private val prefs: TextSecurePreferences,
    private val groupMessageHandler: Provider<GroupMessageHandler>,
    private val messageExpirationManager: Provider<SSKEnvironment.MessageExpirationManagerProtocol>,
    private val messageDataProvider: MessageDataProvider,
    @param:ManagerScope private val scope: CoroutineScope,
    private val notificationManager: MessageNotifier,
    private val messageRequestResponseHandler: Provider<MessageRequestResponseHandler>,
    private val visibleMessageHandler: Provider<VisibleMessageHandler>,
) {
    private val threadMutexes = ConcurrentHashMap<Address.Conversable, ReentrantLock>()


    /**
     * Start a message processing session, ensuring that thread updates and notifications are handled
     * once the whole processing is complete.
     *
     * Note: the context passed to the block is not thread-safe, so it should not be shared between threads.
     */
    fun <T> startProcessing(block: (MessageProcessingContext) -> T): T {
        val context = MessageProcessingContext()
        try {
            return block(context)
        } finally {
            for (threadId in context.threadIDs.values) {
                if (context.maxOutgoingMessageTimestamp > 0L &&
                    context.maxOutgoingMessageTimestamp > storage.getLastSeen(threadId)) {
                    storage.markConversationAsRead(threadId, context.maxOutgoingMessageTimestamp, force = true)
                }

                storage.updateThread(threadId, true)
                notificationManager.updateNotification(this.context, threadId)
            }
        }
    }

    fun processEnvelopedMessage(
        context: MessageProcessingContext,
        threadAddress: Address.Conversable,
        message: Message,
        proto: SignalServiceProtos.Content,
    ) = threadMutexes.getOrPut(threadAddress) { ReentrantLock() }.withLock {
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
            return@withLock
        }

        // Get or create thread ID, if we aren't allowed to create it, and it doesn't exist, drop the message
        val threadId = context.threadIDs[threadAddress] ?:
            if (shouldCreateThread(message)) {
                threadDatabase.getOrCreateThreadIdFor(threadAddress)
                    .also { context.threadIDs[threadAddress] = it }
            } else {
                threadDatabase.getThreadIdIfExistsFor(threadAddress)
                    .also { id ->
                        if (id == -1L) {
                            log { "Dropping message for non-existing thread ${threadAddress.debugString}" }
                            return@withLock
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
                groupId = (threadAddress as? Address.Group)?.accountId
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
            is MessageRequestResponse -> messageRequestResponseHandler.get().handleExplicitRequestResponseMessage(message)
            is VisibleMessage -> {
                if (message.isSenderSelf &&
                    message.sentTimestamp != null &&
                    message.sentTimestamp!! > context.maxOutgoingMessageTimestamp) {
                    context.maxOutgoingMessageTimestamp = message.sentTimestamp!!
                }

                visibleMessageHandler.get().handleVisibleMessage(
                    message = message,
                    threadId = threadId,
                    threadAddress = threadAddress,
                    ctx = context,
                    proto = proto,
                    runThreadUpdate = false,
                    runProfileUpdate = true,
                )
            }

            is CallMessage -> handleCallMessage(message)
        }

    }

    fun processCommunityMessage(
        threadAddress: Address.Community,
        message: OpenGroupMessage,
        context: MessageProcessingContext
    ) = threadMutexes.getOrPut(threadAddress) { ReentrantLock() }.withLock {

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
        if(!prefs.isTypingIndicatorsEnabled()) return

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
        return message is VisibleMessage
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

        val notification: DataExtractionNotificationInfoMessage = when(message.kind) {
            is DataExtractionNotification.Kind.MediaSaved -> DataExtractionNotificationInfoMessage(DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED)
            else -> return
        }
        storage.insertDataExtractionNotificationMessage(senderPublicKey, notification, message.sentTimestamp!!)
    }

    fun handleUnsendRequest(message: UnsendRequest): MessageId? {
        val userPublicKey = storage.getUserPublicKey()
        val userAuth = storage.userAuth ?: return null
        val isLegacyGroupAdmin: Boolean = message.groupPublicKey?.let { key ->
            var admin = false
            val groupID = doubleEncodeGroupID(key)
            val group = storage.getGroup(groupID)
            if(group != null) {
                admin = group.admins.map { it.toString() }.contains(message.sender)
            }
            admin
        } ?: false

        // First we need to determine the validity of the UnsendRequest
        // It is valid if:
        val requestIsValid = message.sender == message.author || //  the sender is the author of the message
                message.author == userPublicKey || //  the sender is the current user
                isLegacyGroupAdmin // sender is an admin of legacy group

        if (!requestIsValid) { return null }

        val timestamp = message.timestamp ?: return null
        val author = message.author ?: return null
        val messageToDelete = storage.getMessageByTimestamp(timestamp, author, false) ?: return null
        val messageIdToDelete = messageToDelete.messageId
        val messageType = messageToDelete.individualRecipient?.getType()

        // send a /delete rquest for 1on1 messages
        if (messageType == MessageType.ONE_ON_ONE) {
            messageDataProvider.getServerHashForMessage(messageIdToDelete)?.let { serverHash ->
                scope.launch(Dispatchers.IO) { // using scope as we are slowly migrating to coroutines but we can't migrate everything at once
                    try {
                        SnodeAPI.deleteMessage(author, userAuth, listOf(serverHash))
                    } catch (e: Exception) {
                        Log.e("Loki", "Failed to delete message", e)
                    }
                }
            }
        }

        // the message is marked as deleted locally
        // except for 'note to self' where the message is completely deleted
        if (messageType == MessageType.NOTE_TO_SELF){
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
    private fun shouldDiscardForHiddenContact(ctx: MessageProcessingContext,
                                              messageTimestamp: Long,
                                              threadAddress: Address.Standard): Boolean {
        val hidden = configFactory.withUserConfigs { configs ->
            configs.contacts.get(threadAddress.address)?.priority == ConfigBase.PRIORITY_HIDDEN
        }

        return hidden &&
                // the message's sentTimestamp is earlier than the sentTimestamp of the last config
                messageTimestamp < ctx.contactConfigTimestamp
    }

    inner class MessageProcessingContext(
        val recipients: HashMap<Address.Conversable, Recipient> = hashMapOf(),
        val threadIDs: HashMap<Address.Conversable, Long> = hashMapOf(),
        val currentUserBlindedKeys: HashMap<Address.Community, List<String>> = hashMapOf(),
        val currentUserPublicKey: String = requireNotNull(storage.getUserPublicKey()) {
            "No current user available"
        },
        var maxOutgoingMessageTimestamp: Long = 0L,
    ) {
        val contactConfigTimestamp: Long by lazy(LazyThreadSafetyMode.NONE) {
            configFactory.getConfigTimestamp(UserConfigType.CONTACTS, currentUserPublicKey)
        }

        fun getThreadRecipient(threadAddress: Address.Conversable): Recipient {
            return recipients.getOrPut(threadAddress) {
                recipientRepository.getRecipientSync(threadAddress)
            }
        }

        fun getCurrentUserBlindedKeysByThread(address: Address.Conversable): List<String> {
            if (address !is Address.Community) return emptyList()
            return currentUserBlindedKeys.getOrPut(address) {
                BlindKeyAPI.blind15Ids(
                    sessionId = currentUserPublicKey,
                    serverPubKey = requireNotNull(storage.getOpenGroupPublicKey(address.serverUrl)) {
                        "No open group public key for community ${address.debugString}"
                    }
                )
            }
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
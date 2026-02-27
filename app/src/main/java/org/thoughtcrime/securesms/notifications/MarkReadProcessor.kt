package org.thoughtcrime.securesms.notifications

import androidx.collection.LongLongMap
import androidx.collection.MutableLongLongMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.database.MessageUpdateNotification
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.findIncomingMessages
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getMessages
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.getAllLastSeen
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.preferences.CommunicationPreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * This component reacts to changes to lastSeen for each thread and perform various logic
 * upon it. Right now it handles:
 *
 * 1. Sending read receipt back to sender
 * 2. Starting disappearing message logic for AFTER_READ mode
 *
 * Because the reactivity of this component, there is no need to manually perform read receipt sending,
 * or disappearing message logic anywhere else in the code, this component will be able to
 * handle them as changes arise.
 */
@Singleton
class MarkReadProcessor @Inject constructor(
    private val recipientRepository: RecipientRepository,
    private val messageSender: MessageSender,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val threadDb: ThreadDatabase,
    private val snodeClock: SnodeClock,
    private val prefs: PreferenceStorage,
    @param:ManagerScope private val scope: CoroutineScope,
) : AuthAwareComponent {
    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState): Unit = supervisorScope {
        val threadLastSeenFlow = threadDb.updateNotifications
            .map { id ->
                threadDb.getAddressAndLastSeen(id)?.let { (address, lastSeen) ->
                    ThreadUpdated(id, address, lastSeen)
                }
            }
            .filterNotNull()
            .distinctUntilChanged()
            .shareIn(this, SharingStarted.Lazily)

        val messageAddedFlow = merge(
            mmsDatabase.changeNotification,
            smsDatabase.updateNotification,
        ).filter { it.changeType == MessageUpdateNotification.ChangeType.Added }
            .shareIn(this, SharingStarted.Lazily)

        launch {
            try {
                handleReadReceiptSending(threadLastSeenFlow, messageAddedFlow)
            } catch (e: Throwable) {
                Log.e(TAG, "Error handling read receipt sending", e)
                if (e is CancellationException) throw e
            }
        }

        launch {
            try {
                handleAfterReadDisappearingMessages(threadLastSeenFlow, messageAddedFlow)
            } catch (e: Throwable) {
                Log.e(TAG, "Error handling after read disappearing messages", e)
                if (e is CancellationException) throw e
            }
        }
    }

    private data class ThreadUpdated(
        val threadId: Long,
        val threadAddress: Address.Conversable,
        val lastSeenMs: Long
    )

    private data class State<T>(
        val lastSeenByThreadIDs: LongLongMap,
        val updates: T? = null,
    )

    /**
     * Look for messages that need sending read receipt to, when the read receipt is enabled.
     */
    private suspend fun handleReadReceiptSending(
        threadLastSeenFlow: SharedFlow<ThreadUpdated>,
        messageAddedFlow: SharedFlow<MessageUpdateNotification>
    ) {
        class Updates(val threadAddress: Address, val messageTimestamps: List<Long>)

        @Suppress("OPT_IN_USAGE")
        prefs.watch(scope, CommunicationPreferences.READ_RECEIPT_ENABLED)
            .flatMapLatest { enabled ->
                if (!enabled) {
                    Log.d(TAG, "Read receipts disabled, skipping")
                    return@flatMapLatest emptyFlow()
                }

                /**
                 * The flow below bases on a state (the [State]), and accept two events:
                 * 1. Thread last seen updated
                 * 2. Message added
                 *
                 * When "1. Thread last seen updated": query all the messages between old last seen and
                 * new last seen to figure out which messages are newly eligible for sending read receipt.
                 *
                 * When "2. Message added": look at the added messages and check if they should
                 * be regarded as eligible for sending read receipt, by comparing to current state.
                 *
                 * The end result is the [Updates] which contains the message timestamps that need to send
                 * receipt to.
                 *
                 * There are other nuisances in the flow where we try not to query db unnecessarily
                 * when we don't do read receipts for those threads anyway.
                 */
                merge(threadLastSeenFlow, messageAddedFlow,)
                    .scan(State<Updates>(threadDb.getAllLastSeen())) { acc, event ->
                        when (event) {
                            is MessageUpdateNotification -> {
                                State(
                                    lastSeenByThreadIDs = acc.lastSeenByThreadIDs,
                                    updates = threadDb.getRecipientForThreadId(event.threadId)
                                        ?.takeIf(::eligibleForReadReceipt)
                                        ?.let { threadAddress ->
                                            val threadLastSeen =
                                                acc.lastSeenByThreadIDs.getOrDefault(
                                                    event.threadId,
                                                    0L
                                                )
                                            mmsSmsDatabase.getMessages(event.ids)
                                                .mapNotNull { msg ->
                                                    msg.dateSent.takeIf {
                                                        msg.eligibleForReadReceipt(
                                                            threadLastSeen
                                                        )
                                                    }
                                                }
                                                .takeIf { it.isNotEmpty() }
                                                ?.also {
                                                    Log.d(
                                                        TAG,
                                                        "New message(s) in thread ${event.threadId} eligible for read receipt"
                                                    )
                                                }
                                                ?.let { Updates(threadAddress, it) }
                                        }
                                )
                            }

                            is ThreadUpdated -> {
                                // Thread updated, look at the last seen to determine if we are truly updated
                                val oldLastSeen =
                                    acc.lastSeenByThreadIDs.getOrDefault(event.threadId, 0L)

                                if (event.lastSeenMs > oldLastSeen) {
                                    Log.d(
                                        TAG,
                                        "Thread ${event.threadId} lastSeen advanced $oldLastSeen -> ${event.lastSeenMs}"
                                    )
                                    State(
                                        lastSeenByThreadIDs = acc.lastSeenByThreadIDs.updated(
                                            event.threadId,
                                            event.lastSeenMs
                                        ),
                                        updates = if (eligibleForReadReceipt(event.threadAddress)) {
                                            mmsSmsDatabase.findIncomingMessages(
                                                event.threadId,
                                                oldLastSeen,
                                                event.lastSeenMs
                                            ).mapNotNull { msg ->
                                                msg.dateSent.takeIf {
                                                    msg.eligibleForReadReceipt(
                                                        event.lastSeenMs
                                                    )
                                                }
                                            }.takeIf { it.isNotEmpty() }
                                                ?.also {
                                                    Log.d(
                                                        TAG,
                                                        "Sending read receipt for ${it.size} message(s) in thread ${event.threadId}"
                                                    )
                                                }
                                                ?.let { Updates(event.threadAddress, it) }
                                        } else {
                                            Log.d(
                                                TAG,
                                                "Thread ${event.threadId} not eligible for read receipt, skipping"
                                            )
                                            null
                                        }
                                    )
                                } else if (acc.updates != null) {
                                    acc.copy(updates = null)
                                } else {
                                    acc
                                }
                            }

                            else -> error("Unexpected event type $event")
                        }
                    }.mapNotNull { it.updates }
            }
            // Must NOT use collectLatest as "updates" data is an "event" rather than a state: it
            // does not persist between emissions. Using collectLatest will potentially cause
            // data loss.
            .collect { updates ->
                Log.d(TAG, "Sending read receipts to ${updates.messageTimestamps.size} messages")

                val message = ReadReceipt(updates.messageTimestamps).apply {
                    sentTimestamp = snodeClock.currentTimeMillis()
                }

                messageSender.send(message, updates.threadAddress)
            }
    }

    private fun eligibleForReadReceipt(threadAddress: Address): Boolean {
        if (threadAddress is Address.GroupLike) {
            // Read receipts don't get sent to any group like conversations
            return false
        }

        val recipient = recipientRepository.getRecipientSync(threadAddress)

        return (recipient.data as? RecipientData.Contact)?.let {
            it.approved && !it.blocked
        } == true
    }

    private suspend fun handleAfterReadDisappearingMessages(
        threadLastSeenFlow: SharedFlow<ThreadUpdated>,
        messageAddedFlow: SharedFlow<MessageUpdateNotification>
    ) {
        class ExpiryUpdates(val messageIds: List<MessageId>, val expireStarted: Long)

        merge(threadLastSeenFlow, messageAddedFlow)
            .scan(State<ExpiryUpdates>(threadDb.getAllLastSeen())) { acc, event ->
                when (event) {
                    is MessageUpdateNotification -> {
                        val threadLastSeen = acc.lastSeenByThreadIDs.getOrDefault(event.threadId, 0L)
                        State(
                            lastSeenByThreadIDs = acc.lastSeenByThreadIDs,
                            updates = mmsSmsDatabase.getMessages(event.ids)
                                .filter { msg -> msg.eligibleForAfterReadExpiry(threadLastSeen) }
                                .map { it.messageId }
                                .takeIf { it.isNotEmpty() }
                                ?.also { Log.d(TAG, "New message(s) in thread ${event.threadId} eligible for AFTER_READ expiry") }
                                ?.let { ExpiryUpdates(it, expireStarted = snodeClock.currentTimeMillis()) }
                        )
                    }

                    is ThreadUpdated -> {
                        val oldLastSeen = acc.lastSeenByThreadIDs.getOrDefault(event.threadId, 0L)
                        if (event.lastSeenMs > oldLastSeen) {
                            State(
                                lastSeenByThreadIDs = acc.lastSeenByThreadIDs.updated(
                                    event.threadId,
                                    event.lastSeenMs
                                ),
                                updates = mmsSmsDatabase.findIncomingMessages(
                                    event.threadId,
                                    oldLastSeen,
                                    event.lastSeenMs
                                ).filter { msg -> msg.eligibleForAfterReadExpiry(event.lastSeenMs) }
                                    .map { it.messageId }
                                    .takeIf { it.isNotEmpty() }
                                    ?.also { Log.d(TAG, "Starting AFTER_READ expiry for ${it.size} message(s) in thread ${event.threadId}") }
                                    ?.let { ExpiryUpdates(it, expireStarted = snodeClock.currentTimeMillis()) }
                            )
                        } else if (acc.updates != null) {
                            acc.copy(updates = null)
                        } else {
                            acc
                        }
                    }

                    else -> error("Unknown event type $event")
                }
            }.mapNotNull { it.updates }
            .collect { updates ->
                Log.d(TAG, "Marking expiry started for ${updates.messageIds.size} message(s) at ${updates.expireStarted}")
                for (messageId in updates.messageIds) {
                    if (messageId.mms) {
                        mmsDatabase.markExpireStarted(messageId.id, updates.expireStarted)
                    } else {
                        smsDatabase.markExpireStarted(messageId.id, updates.expireStarted)
                    }
                }
            }
    }

    companion object {
        private fun MessageRecord.eligibleForReadReceipt(maxSentTimeMsInclusive: Long): Boolean {
            return isIncoming && !isControlMessage && dateSent <= maxSentTimeMsInclusive
        }

        /**
         * Determines whether this message should have its expiry timer started as a result of
         * the thread being read up to [lastSeenMs].
         *
         * The AFTER_READ expiry mode is encoded in the message columns rather than as an explicit
         * mode field: [MessageRecord.expiresIn] > 0 means expiry is configured, and
         * [MessageRecord.expireStarted] == 0 means the timer hasn't started (i.e. AFTER_READ).
         * AFTER_SEND messages already have expireStarted = sentTimestamp on insertion, so they are
         * implicitly excluded.
         *
         * Control message exceptions are also handled at insertion time and need no special casing
         * here: MessageRequestResponse is inserted with expiresIn = 0; CallMessage is coerced to
         * AFTER_SEND so expireStarted != 0. Other control messages (e.g. timer updates) are
         * intentionally eligible and follow the same rules as regular messages.
         *
         * Only incoming messages are handled here; outgoing timers are started by
         * [ExpiringMessageManager] at send time.
         */
        private fun MessageRecord.eligibleForAfterReadExpiry(lastSeenMs: Long): Boolean {
            return isIncoming && expiresIn > 0 && expireStarted == 0L && dateSent <= lastSeenMs
        }

        // Copy the existing map and add the new item
        private fun LongLongMap.updated(key: Long, value: Long): LongLongMap {
            val hasItem = containsKey(key)
            val map = MutableLongLongMap(size + if (hasItem) 0 else 1)
            map[key] = value
            return map
        }


        private const val TAG = "MarkReadProcessor"
    }
}
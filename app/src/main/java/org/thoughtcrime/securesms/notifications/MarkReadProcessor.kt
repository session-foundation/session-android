package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.collection.LongLongMap
import androidx.collection.MutableLongLongMap
import dagger.hilt.android.qualifiers.ApplicationContext
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
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.snode.AlterTtlApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.LokiMessageDatabase
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
    @param:ApplicationContext private val context: Context,
    private val recipientRepository: RecipientRepository,
    private val messageSender: MessageSender,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val threadDb: ThreadDatabase,
    private val storage: StorageProtocol,
    private val snodeClock: SnodeClock,
    private val lokiMessageDatabase: LokiMessageDatabase,
    private val swarmApiExecutor: SwarmApiExecutor,
    private val alterTtyFactory: AlterTtlApi.Factory,
    private val prefs: PreferenceStorage,
    private val loginStateRepository: LoginStateRepository,
    private val configFactory: ConfigFactoryProtocol,
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

    private class Updates<T>(
        val threadAddress: Address,
        val changes: T,
    )

    private data class State<T>(
        val lastSeenByThreadIDs: LongLongMap,
        val updates: Updates<T>? = null,
    )

    /**
     * Look for messages that need sending read receipt to, when the read receipt is enabled.
     */
    private suspend fun handleReadReceiptSending(
        threadLastSeenFlow: SharedFlow<ThreadUpdated>,
        messageAddedFlow: SharedFlow<MessageUpdateNotification>
    ) {
        @Suppress("OPT_IN_USAGE")
        prefs.watch(scope, CommunicationPreferences.READ_RECEIPT_ENABLED)
            .flatMapLatest { enabled ->
                if (!enabled) {
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
                merge(
                    threadLastSeenFlow,
                    messageAddedFlow,
                ).scan(State<List<Long>>(threadDb.getAllLastSeen())) { acc, event ->
                    when (event) {
                        is MessageUpdateNotification -> {
                            State(
                                lastSeenByThreadIDs = acc.lastSeenByThreadIDs,
                                updates = threadDb.getRecipientForThreadId(event.threadId)
                                    ?.takeIf(::eligibleForReadReceipt)
                                    ?.let { threadAddress ->
                                        val threadLastSeen =
                                            acc.lastSeenByThreadIDs.getOrDefault(event.threadId, 0L)
                                        mmsSmsDatabase.getMessages(event.ids)
                                            .mapNotNull { msg ->
                                                msg.dateSent.takeIf { msg.eligibleForReadReceipt(threadLastSeen) }
                                            }
                                            .takeIf { it.isNotEmpty() }
                                            ?.let { Updates(threadAddress, it) }
                                    }
                            )
                        }

                        is ThreadUpdated -> {
                            // Thread updated, look at the last seen to determine if we are truly updated
                            val oldLastSeen =
                                acc.lastSeenByThreadIDs.getOrDefault(event.threadId, 0L)

                            if (event.lastSeenMs > oldLastSeen) {
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
                                            msg.dateSent.takeIf { msg.eligibleForReadReceipt(event.lastSeenMs) }
                                        }.takeIf { it.isNotEmpty() }
                                            ?.let { Updates(event.threadAddress, it) }
                                    } else {
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
                Log.d(TAG, "Sending read receipts to ${updates.changes.size} messages")

                val message = ReadReceipt(updates.changes).apply {
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
        merge(threadLastSeenFlow, messageAddedFlow)
            .scan(State<List<MessageId>>(threadDb.getAllLastSeen())) { acc, event ->
                when (event) {
                    is MessageUpdateNotification -> {}
                    is ThreadUpdated -> {
                        State(
                            lastSeenByThreadIDs = acc.lastSeenByThreadIDs.updated(event.threadId, event.lastSeenMs),
                            updates = null
                        )
                    }
                    else -> error("Unknown event type $event")
                }

                TODO()
            }.mapNotNull { state ->
                state.updates
            }.collect { updates ->
                TODO()
            }
    }

    companion object {
        private fun MessageRecord.eligibleForReadReceipt(maxSentTimeMsInclusive: Long): Boolean {
            return isIncoming && !isControlMessage && dateSent <= maxSentTimeMsInclusive
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
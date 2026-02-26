package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.collection.LongLongMap
import androidx.collection.MutableLongLongMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences.Companion.isReadReceiptsEnabled
import org.session.libsession.utilities.associateByNotNull
import org.session.libsession.utilities.isGroupOrCommunity
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.snode.AlterTtlApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.api.swarm.execute
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.conversation.disappearingmessages.ExpiryType
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MarkedMessageInfo
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.findIncomingMessages
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.getAllLastSeen
import org.thoughtcrime.securesms.database.model.content.DisappearingMessageUpdate
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

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

    init {
        scope.launch {
            merge(
                smsDatabase.updateNotification,
                mmsDatabase.changeNotification
            ).collect {
                Log.d(TAG, "Message change: $it")
            }
        }
    }

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        processLastSeenChanges()
    }

    private fun LongLongMap.updated(key: Long, value: Long): LongLongMap {
        val hasItem = containsKey(key)
        val map = MutableLongLongMap(size + if (hasItem) 0 else 1)
        map[key] = value
        return map
    }

    private suspend fun processLastSeenChanges() {
        class Updated(
            val address: Address.Conversable,
            val threadId: Long,
            val oldLastSeenMs: Long,
            val newLastSeenMs: Long,
        )

        class State(
            val lastSeenByThreadIDs: LongLongMap,
            val updated: Updated? = null,
        )

        threadDb.updateNotifications
            .scan(State(threadDb.getAllLastSeen())) { acc, threadId ->
                val oldLastSeen = acc.lastSeenByThreadIDs.getOrDefault(threadId, -1L)
                val r = threadDb.getAddressAndLastSeen(threadId)
                val newLastSeen = r?.second?.toEpochMilliseconds() ?: 0L

                if (oldLastSeen == newLastSeen) {
                    acc
                } else {
                    State(
                        lastSeenByThreadIDs = acc.lastSeenByThreadIDs.updated(threadId, newLastSeen),
                        updated = if (r != null && oldLastSeen >= 0L) {
                            Updated(
                                address = r.first,
                                threadId = threadId,
                                oldLastSeenMs = oldLastSeen,
                                newLastSeenMs = newLastSeen,
                            )
                        } else {
                            null
                        }
                    )
                }
            }
            .mapNotNull { it.updated }
            // Must NOT use collectLatest as "updated" data is an "event" rather than a state: it
            // does not persist between emissions. Using collectLatest will potentially cause
            // data loss.
            .collect { updated ->
                val threadRecipient = recipientRepository.getRecipient(updated.address)

                val shouldSendReadReceipt = when (updated.address) {
                    is Address.GroupLike -> {
                        // It's impossible to send read receipts to any group like conversation
                        false
                    }

                    is Address.CommunityBlindedId -> {
                        // Read receipt doesn't apply on the blinded convo
                        false
                    }

                    is Address.Standard -> {
                        // For 1on1 convo, first check if we have read receipts enabled
                        if (!isReadReceiptsEnabled(context)) {
                            false
                        } else {
                            // For contacts scenario, we only send read receipt to approved and
                            // non-blocked contacts
                            (threadRecipient.data as? RecipientData.Contact)?.let {
                                it.approved && !it.blocked
                            } == true
                        }
                    }
                }

                val shouldStartExpiringMessages = threadRecipient.expiryMode is ExpiryMode.AfterRead

                if (!shouldSendReadReceipt && !shouldStartExpiringMessages) {
                    // Nothing to do
                    Log.d(TAG, "Thread(${updated.address.debugString}) changes but no action required")
                    return@collect
                }

                val affectedMessageIDs = mmsSmsDatabase.findIncomingMessages(
                    threadId = updated.threadId,
                    startMsExclusive = updated.oldLastSeenMs,
                    endMsInclusive = updated.newLastSeenMs,
                )
            }
    }


    fun process(
        markedReadMessages: List<MarkedMessageInfo>
    ) {
        if (markedReadMessages.isEmpty()) return

        sendReadReceipts(
            markedReadMessages = markedReadMessages
        )


        // start disappear after read messages except TimerUpdates in groups.
        markedReadMessages
            .asSequence()
            .filter { it.expiryType == ExpiryType.AFTER_READ }
            .filter { mmsSmsDatabase.getMessageById(it.expirationInfo.id)?.run {
                (messageContent is DisappearingMessageUpdate)
                        && threadDb.getRecipientForThreadId(threadId)?.isGroupOrCommunity == true } == false
            }
            .forEach {
                val db = if (it.expirationInfo.id.mms) {
                    mmsDatabase
                } else {
                    smsDatabase
                }

                Log.d(TAG, "Marking message ${it.expirationInfo.id.id} as started for disappear after read")
                db.markExpireStarted(it.expirationInfo.id.id, snodeClock.currentTimeMillis())
            }

        hashToDisappearAfterReadMessage(markedReadMessages)?.let(this::shortenExpiryOfDisappearingAfterRead)
    }

    private fun hashToDisappearAfterReadMessage(
        markedReadMessages: List<MarkedMessageInfo>
    ): Map<String, MarkedMessageInfo>? {
        return markedReadMessages
            .filter { it.expiryType == ExpiryType.AFTER_READ }
            .associateByNotNull { it.expirationInfo.run { lokiMessageDatabase.getMessageServerHash(id) } }
            .takeIf { it.isNotEmpty() }
    }

    private fun shortenExpiryOfDisappearingAfterRead(
        hashToMessage: Map<String, MarkedMessageInfo>
    ) {
        scope.launch {
            val userAuth = checkNotNull(storage.userAuth) { "No authorized user" }

            hashToMessage.entries
                .groupBy(
                    keySelector = { it.value.expirationInfo.expiresIn },
                    valueTransform = { it.key }
                ).forEach { (expiresIn, hashes) ->
                    try {
                        swarmApiExecutor.execute(
                            SwarmApiRequest(
                                swarmPubKeyHex = userAuth.accountId.hexString,
                                api = alterTtyFactory.create(
                                    messageHashes = hashes,
                                    auth = userAuth,
                                    alterType = AlterTtlApi.AlterType.Shorten,
                                    newExpiry = snodeClock.currentTimeMillis() + expiresIn
                                )
                            )
                        )
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e

                        Log.e(TAG, "Failed to shorten expiry for messages with hashes $hashes", e)
                    }
                }
        }
    }

    private val Recipient.shouldSendReadReceipt: Boolean
        get() = when (data) {
            is RecipientData.Contact -> approved && !blocked
            else -> false
        }

    private fun sendReadReceipts(
        markedReadMessages: List<MarkedMessageInfo>
    ) {
        if (!isReadReceiptsEnabled(context)) return

        markedReadMessages.map { it.syncMessageId }
            .filter { recipientRepository.getRecipientSync(it.address).shouldSendReadReceipt }
            .groupBy { it.address }
            .forEach { (address, messages) ->
                messages.map { it.timetamp }
                    .let(::ReadReceipt)
                    .apply { sentTimestamp = snodeClock.currentTimeMillis() }
                    .let { messageSender.send(it, address) }
            }
    }

    companion object {
        private const val TAG = "MarkReadProcessor"
    }
}
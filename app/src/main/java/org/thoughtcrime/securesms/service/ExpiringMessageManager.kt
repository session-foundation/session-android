package org.thoughtcrime.securesms.service

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.LegacyGroupControlMessage
import org.session.libsession.messaging.messages.signal.IncomingMediaMessage
import org.session.libsession.messaging.messages.signal.OutgoingExpirationUpdateMessage
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.GroupUtil.doubleEncodeGroupID
import org.session.libsession.utilities.SSKEnvironment.MessageExpirationManagerProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.mms.MmsException
import java.io.IOException
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

private val TAG = ExpiringMessageManager::class.java.simpleName

@Singleton
class ExpiringMessageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsDatabase: SmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val clock: SnodeClock,
    private val storage: Lazy<Storage>,
    private val preferences: TextSecurePreferences,
) : MessageExpirationManagerProtocol {

    private val scheduleDeletionChannel: SendChannel<ExpiringMessageReference>

    init {
        val channel = Channel<ExpiringMessageReference>(capacity = Channel.UNLIMITED)
        scheduleDeletionChannel = channel

        GlobalScope.launch {
            preferences.watchLocalNumber()
                .map { it != null }
                .distinctUntilChanged()
                .collectLatest { loggedIn ->
                    if (loggedIn) {
                        try {
                            process(channel)
                        } catch (ec: CancellationException) {
                            throw ec
                        }
                        catch (ec: Exception) {
                            Log.e(TAG, "Error processing expiring messages", ec)
                        }
                    }
                }
        }
    }

    private fun getDatabase(mms: Boolean) = if (mms) mmsDatabase else smsDatabase

    fun scheduleDeletion(id: MessageId, expireStartedAt: Long, expiresInMills: Long) {
        check(expireStartedAt > 0 && expiresInMills > 0) {
            "Expiration start time and duration must be greater than zero."
        }

        getDatabase(id.mms).markExpireStarted(id.id, expireStartedAt)
        scheduleDeletionChannel.trySend(ExpiringMessageReference(id, expireStartedAt + expiresInMills))
    }

    private fun insertIncomingExpirationTimerMessage(
        message: ExpirationTimerUpdate,
        expireStartedAt: Long
    ): MessageId? {
        val senderPublicKey = message.sender
        val sentTimestamp = message.sentTimestamp
        val groupId = message.groupPublicKey
        val expiresInMillis = message.expiryMode.expiryMillis
        var groupInfo = Optional.absent<SignalServiceGroup?>()
        val address = fromSerialized(senderPublicKey!!)
        var recipient = Recipient.from(context, address, false)

        // if the sender is blocked, we don't display the update, except if it's in a closed group
        if (recipient.isBlocked && groupId == null) return null
        return try {
            if (groupId != null) {
                val groupAddress: Address
                groupInfo = when {
                    groupId.startsWith(IdPrefix.GROUP.value) -> {
                        groupAddress = fromSerialized(groupId)
                        Optional.of(SignalServiceGroup(Hex.fromStringCondensed(groupId), SignalServiceGroup.GroupType.SIGNAL))
                    }
                    else -> {
                        val doubleEncoded = GroupUtil.doubleEncodeGroupID(groupId)
                        groupAddress = fromSerialized(doubleEncoded)
                        Optional.of(SignalServiceGroup(GroupUtil.getDecodedGroupIDAsData(doubleEncoded), SignalServiceGroup.GroupType.SIGNAL))
                    }
                }
                recipient = Recipient.from(context, groupAddress, false)
            }
            val threadId = storage.get().getThreadId(recipient) ?: return null
            val mediaMessage = IncomingMediaMessage(
                address, sentTimestamp!!, -1,
                expiresInMillis, expireStartedAt, true,
                false,
                false,
                Optional.absent(),
                groupInfo,
                Optional.absent(),
                Optional.absent(),
                Optional.absent(),
                Optional.absent(),
                Optional.absent()
            )
            //insert the timer update message
            mmsDatabase.insertSecureDecryptedMessageInbox(mediaMessage, threadId, runThreadUpdate = true)
                .orNull()
                ?.let { MessageId(it.messageId, mms = true) }
        } catch (ioe: IOException) {
            Log.e("Loki", "Failed to insert expiration update message.")
            null
        } catch (ioe: MmsException) {
            Log.e("Loki", "Failed to insert expiration update message.")
            null
        }
    }

    private fun insertOutgoingExpirationTimerMessage(
        message: ExpirationTimerUpdate,
        expireStartedAt: Long
    ): MessageId? {
        val sentTimestamp = message.sentTimestamp
        val groupId = message.groupPublicKey
        val duration = message.expiryMode.expiryMillis
        try {
            val serializedAddress = when {
                groupId == null -> message.syncTarget ?: message.recipient!!
                groupId.startsWith(IdPrefix.GROUP.value) -> groupId
                else -> doubleEncodeGroupID(groupId)
            }
            val address = fromSerialized(serializedAddress)
            val recipient = Recipient.from(context, address, false)

            message.threadID = storage.get().getOrCreateThreadIdFor(address)
            val timerUpdateMessage = OutgoingExpirationUpdateMessage(
                recipient,
                sentTimestamp!!,
                duration,
                expireStartedAt,
                groupId
            )
            return mmsDatabase.insertSecureDecryptedMessageOutbox(
                timerUpdateMessage,
                message.threadID!!,
                sentTimestamp,
                true
            ).orNull()?.messageId?.let { MessageId(it, mms = true) }
        } catch (ioe: MmsException) {
            Log.e("Loki", "Failed to insert expiration update message.", ioe)
            return null
        } catch (ioe: IOException) {
            Log.e("Loki", "Failed to insert expiration update message.", ioe)
            return null
        }
    }

    override fun insertExpirationTimerMessage(message: ExpirationTimerUpdate) {
        val expiryMode: ExpiryMode = message.expiryMode

        val userPublicKey = preferences.getLocalNumber()
        val senderPublicKey = message.sender
        val sentTimestamp = message.sentTimestamp ?: 0
        val expireStartedAt = if ((expiryMode is ExpiryMode.AfterSend || message.isSenderSelf) && !message.isGroup) sentTimestamp else 0

        // Notify the user
        val messageId = if (senderPublicKey == null || userPublicKey == senderPublicKey) {
            // sender is self or a linked device
            insertOutgoingExpirationTimerMessage(message, expireStartedAt)
        } else {
            insertIncomingExpirationTimerMessage(message, expireStartedAt)
        }

        if (messageId != null) {
            startExpiringNow(messageId)
        }
    }

    override fun startExpiringNow(id: MessageId) {
        val message = mmsSmsDatabase.getMessageById(id) ?: run {
            Log.w(TAG, "Message with ID $id not found in database, cannot start expiration.")
            return
        }

        if (message.expiresIn <= 0L) {
            Log.w(TAG, "Message with ID $message has no expiration mode set, cannot start expiration.")
            return
        }

        scheduleDeletion(message.messageId, clock.currentTimeMills(), message.expiresIn)
    }

    override fun onMessageSent(message: Message) {
        // When a message is sent, we'll schedule deletion immediately if we have an expiry mode,
        // even if the expiry mode is set to AfterRead, as we don't have a reliable way to know
        // that the recipient has read the message at at all. From our perspective it's better
        // to disappear the message regardlessly for the safety of ourselves.
        // As for the receiver, they will be able to disappear the message correctly after
        // they've done reading it.
        if (message.expiryMode != ExpiryMode.NONE) {
            scheduleMessageDeletion(message)
        }
    }

    override fun onMessageReceived(message: Message) {
        // When we receive a message, we'll schedule deletion if it has an expiry mode set to
        // AfterSend, as the message would be considered sent from the sender's perspective.
        if (message.expiryMode is ExpiryMode.AfterSend) {
            scheduleMessageDeletion(message)
        }
    }

    private fun scheduleMessageDeletion(message: Message) {
        if (
            message is ExpirationTimerUpdate && message.isGroup ||
            message is LegacyGroupControlMessage ||
            message.openGroupServerMessageID != null || // ignore expiration on communities since they do not support disappearing mesasges
            message.expiryMode == ExpiryMode.NONE // no expiration mode set
        ) return

        val id = requireNotNull(message.id) {
            "Message ID cannot be null when scheduling deletion."
        }

        scheduleDeletion(
            id = id,
            expireStartedAt = clock.currentTimeMills(), // The expiration starts now instead of `message.sentTimestamp`, as that property is not really the time the message is sent but the time the user hits send
            expiresInMills = message.expiryMode.expiryMillis
        )
    }

    private suspend fun process(scheduleChannel: ReceiveChannel<ExpiringMessageReference>) {
        // Populate the expiring message queue with initial data from database to start with.
        // This message queue is sorted by the expiration time from closest to furthest
        val sortedMessageQueue = smsDatabase.readerFor(smsDatabase.expirationStartedMessages).use { smsReader ->
            mmsDatabase.expireStartedMessages.use { mmsReader ->
                (generateSequence { smsReader.next } + generateSequence { mmsReader.next })
                    .mapTo(PriorityQueue(), ::ExpiringMessageReference)
            }
        }

        while (true) {
            val millsUntilNextExpiration = sortedMessageQueue.firstOrNull()?.let { it.expiresAtMillis - clock.currentTimeMills() }

            // Wait for the next expiration or a new message to be scheduled
            val newScheduledMessage = if (millsUntilNextExpiration != null && millsUntilNextExpiration > 0L) {
                // There's something in the queue for later, so we'll wait for that, or a new message to be scheduled
                ExpirationListener.setAlarm(context, millsUntilNextExpiration)
                withTimeoutOrNull(millsUntilNextExpiration) {
                    scheduleChannel.receive()
                }
            } else if (millsUntilNextExpiration == null) {
                // There is nothing in the queue, so we'll just wait for a new message to be expired
                scheduleChannel.receive()
            } else {
                // There are some expired messages, so we can process them immediately
                null
            }

            if (newScheduledMessage != null) {
                sortedMessageQueue.add(newScheduledMessage)
            }

            // Drain the queue and process expired messages
            while (sortedMessageQueue.isNotEmpty() && sortedMessageQueue.peek()!!.expiresAtMillis <= clock.currentTimeMills()) {
                val expiredMessage = sortedMessageQueue.remove()
                Log.d(TAG, "Processing expired message: ${expiredMessage.id}")
                getDatabase(expiredMessage.id.mms).deleteMessage(expiredMessage.id.id)
            }
        }
    }

    private data class ExpiringMessageReference(
        val id: MessageId,
        val expiresAtMillis: Long
    ): Comparable<ExpiringMessageReference> {
        constructor(record: MessageRecord): this(
            id = record.messageId,
            expiresAtMillis = record.expireStarted + record.expiresIn
        )

        override fun compareTo(other: ExpiringMessageReference) = compareValuesBy(this, other, { it.expiresAtMillis }, { it.id.id }, { it.id.mms })
    }
}

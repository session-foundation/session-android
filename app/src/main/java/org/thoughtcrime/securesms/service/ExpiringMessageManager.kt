package org.thoughtcrime.securesms.service

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.ExpiryMode.AfterSend
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
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
import org.thoughtcrime.securesms.mms.MmsException
import java.io.IOException
import java.util.TreeSet
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
    private val expiringMessageReferences = TreeSet<ExpiringMessageReference>()
    private val executor: Executor = Executors.newSingleThreadExecutor()

    init {
        executor.execute(LoadTask())
        executor.execute(ProcessTask())
    }

    private fun getDatabase(mms: Boolean) = if (mms) mmsDatabase else smsDatabase

    fun scheduleDeletion(id: Long, mms: Boolean, startedAtTimestamp: Long, expiresInMillis: Long) {
        if (startedAtTimestamp <= 0) return

        val expiresAtMillis = startedAtTimestamp + expiresInMillis
        synchronized(expiringMessageReferences) {
            expiringMessageReferences += ExpiringMessageReference(id, mms, expiresAtMillis)
            (expiringMessageReferences as Object).notifyAll()
        }
    }

    fun checkSchedule() {
        synchronized(expiringMessageReferences) { (expiringMessageReferences as Object).notifyAll() }
    }

    private fun insertIncomingExpirationTimerMessage(
        message: ExpirationTimerUpdate,
        expireStartedAt: Long
    ) {
        val senderPublicKey = message.sender
        val sentTimestamp = message.sentTimestamp
        val groupId = message.groupPublicKey
        val expiresInMillis = message.expiryMode.expiryMillis
        var groupInfo = Optional.absent<SignalServiceGroup?>()
        val address = fromSerialized(senderPublicKey!!)
        var recipient = Recipient.from(context, address, false)

        // if the sender is blocked, we don't display the update, except if it's in a closed group
        if (recipient.isBlocked && groupId == null) return
        try {
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
            val threadId = storage.get().getThreadId(recipient) ?: return
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
        } catch (ioe: IOException) {
            Log.e("Loki", "Failed to insert expiration update message.")
        } catch (ioe: MmsException) {
            Log.e("Loki", "Failed to insert expiration update message.")
        }
    }

    private fun insertOutgoingExpirationTimerMessage(
        message: ExpirationTimerUpdate,
        expireStartedAt: Long
    ) {
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
            mmsDatabase.insertSecureDecryptedMessageOutbox(
                timerUpdateMessage,
                message.threadID!!,
                sentTimestamp,
                true
            )
        } catch (ioe: MmsException) {
            Log.e("Loki", "Failed to insert expiration update message.", ioe)
        } catch (ioe: IOException) {
            Log.e("Loki", "Failed to insert expiration update message.", ioe)
        }
    }

    override fun insertExpirationTimerMessage(message: ExpirationTimerUpdate) {
        val expiryMode: ExpiryMode = message.expiryMode

        val userPublicKey = preferences.getLocalNumber()
        val senderPublicKey = message.sender
        val sentTimestamp = message.sentTimestamp ?: 0
        val expireStartedAt = if ((expiryMode is AfterSend || message.isSenderSelf) && !message.isGroup) sentTimestamp else 0

        // Notify the user
        if (senderPublicKey == null || userPublicKey == senderPublicKey) {
            // sender is self or a linked device
            insertOutgoingExpirationTimerMessage(message, expireStartedAt)
        } else {
            insertIncomingExpirationTimerMessage(message, expireStartedAt)
        }

        maybeStartExpiration(message)
    }

    override fun startAnyExpiration(timestamp: Long, author: String, expireStartedAt: Long) {
        mmsSmsDatabase.getMessageFor(timestamp, author)?.run {
            getDatabase(isMms()).markExpireStarted(getId(), expireStartedAt)
            scheduleDeletion(getId(), isMms(), expireStartedAt, expiresIn)
        } ?: Log.e(TAG, "no message record!")
    }

    private inner class LoadTask : Runnable {
        override fun run() {
            val smsReader = smsDatabase.readerFor(smsDatabase.getExpirationStartedMessages())
            val mmsReader = mmsDatabase.expireStartedMessages

            val smsMessages = smsReader.use { generateSequence { it.next }.toList() }
            val mmsMessages = mmsReader.use { generateSequence { it.next }.toList() }

            (smsMessages + mmsMessages).forEach { messageRecord ->
                expiringMessageReferences += ExpiringMessageReference(
                    messageRecord.getId(),
                    messageRecord.isMms,
                    messageRecord.expireStarted + messageRecord.expiresIn
                )
            }
        }
    }

    private inner class ProcessTask : Runnable {
        override fun run() {
            while (true) {
                synchronized(expiringMessageReferences) {
                    try {
                        while (expiringMessageReferences.isEmpty()) (expiringMessageReferences as Object).wait()
                        val nextReference = expiringMessageReferences.first()
                        val waitTime = nextReference.expiresAtMillis - clock.currentTimeMills()
                        if (waitTime > 0) {
                            ExpirationListener.setAlarm(context, waitTime)
                            (expiringMessageReferences as Object).wait(waitTime)
                            null
                        } else {
                            expiringMessageReferences -= nextReference
                            nextReference
                        }
                    } catch (e: InterruptedException) {
                        Log.w(TAG, e)
                        null
                    }
                }?.run { getDatabase(mms).deleteMessage(id) }
            }
        }
    }

    private data class ExpiringMessageReference(
        val id: Long,
        val mms: Boolean,
        val expiresAtMillis: Long
    ): Comparable<ExpiringMessageReference> {
        override fun compareTo(other: ExpiringMessageReference) = compareValuesBy(this, other, { it.expiresAtMillis }, { it.id }, { it.mms })
    }
}

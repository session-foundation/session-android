package org.thoughtcrime.securesms.service

import dagger.Lazy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.signal.IncomingMediaMessage
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.SSKEnvironment.MessageExpirationManagerProtocol
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.MessagingDatabase
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.content.DisappearingMessageUpdate
import org.thoughtcrime.securesms.mms.MmsException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val TAG = ExpiringMessageManager::class.java.simpleName

/**
 * A manager that reactively looking into the [MmsDatabase] and [SmsDatabase] for expired messages,
 * and deleting them. This is done by observing the expiration timestamps of messages and scheduling
 * the deletion of them when they are expired.
 *
 * There is no need (and no way) to ask this manager to schedule a deletion of a message, instead, all you
 * need to do is set the expiryMills and expiryStarted fields of the message and save to db,
 * this manager will take care of the rest.
 */
@Singleton
class ExpiringMessageManager @Inject constructor(
    private val smsDatabase: SmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val clock: SnodeClock,
    private val storage: Lazy<Storage>,
    private val loginStateRepository: LoginStateRepository,
    private val recipientRepository: RecipientRepository,
    private val threadDatabase: ThreadDatabase,
) : MessageExpirationManagerProtocol, AuthAwareComponent {


    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        supervisorScope {
            launch { processDatabase(smsDatabase) }
            launch { processDatabase(mmsDatabase) }
        }
    }

    private fun getDatabase(mms: Boolean) = if (mms) mmsDatabase else smsDatabase

    private fun insertIncomingExpirationTimerMessage(
        message: ExpirationTimerUpdate,
    ): MessageId? {
        val senderPublicKey = message.sender
        val sentTimestamp = message.sentTimestamp
        val groupAddress = message.groupPublicKey?.toAddress() as? Address.GroupLike
        val expiresInMillis = message.expiryMode.expiryMillis
        val address = senderPublicKey!!.toAddress()
        var recipient = recipientRepository.getRecipientSync(address)

        // if the sender is blocked, we don't display the update, except if it's in a closed group
        if (recipient.blocked && groupAddress == null) return null
        return try {
            if (groupAddress != null) {
                recipient = recipientRepository.getRecipientSync(groupAddress)
            }

            val threadId = recipient.address.let(storage.get()::getThreadId) ?: return null
            val mediaMessage = IncomingMediaMessage(
                from = address,
                sentTimeMillis = sentTimestamp!!,
                expiresIn = expiresInMillis,
                expireStartedAt = 0,  // Marking expiryStartedAt as 0 as expiration logic will be universally applied on received messages
                // We no longer set this to true anymore as it won't be used in the future,
                isMessageRequestResponse = false,
                hasMention = false,
                body = null,
                group = groupAddress,
                attachments = emptyList(),
                proFeatures = emptySet(),
                messageContent = DisappearingMessageUpdate(message.expiryMode),
                quote = null,
                linkPreviews = emptyList(),
                dataExtractionNotification = null
            )
            //insert the timer update message
            mmsDatabase.insertSecureDecryptedMessageInbox(
                mediaMessage,
                threadId,
                runThreadUpdate = true
            )
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
    ): MessageId? {
        val sentTimestamp = message.sentTimestamp
        val groupId = message.groupPublicKey?.toAddress() as? Address.GroupLike
        val duration = message.expiryMode.expiryMillis
        try {
            val serializedAddress =
                groupId ?: (message.syncTarget ?: message.recipient!!).toAddress()

            message.threadID = storage.get().getOrCreateThreadIdFor(serializedAddress)
            val content = DisappearingMessageUpdate(message.expiryMode)
            val timerUpdateMessage = if (groupId != null) OutgoingMediaMessage(
                recipient = serializedAddress,
                body = "",
                group = groupId,
                avatar = null,
                sentTimeMillis = sentTimestamp!!,
                expiresInMillis = duration,
                expireStartedAtMillis = 0, // Marking as 0 as expiration shouldn't start until we send the message
                isGroupUpdateMessage = false,
                quote = null,
                previews = emptyList(),
                messageContent = content
            ) else OutgoingMediaMessage(
                recipient = serializedAddress,
                body = "",
                attachments = emptyList(),
                sentTimeMillis = sentTimestamp!!,
                expiresInMillis = duration,
                expireStartedAtMillis = 0, // Marking as 0 as expiration shouldn't start until we send the message
                outgoingQuote = null,
                messageContent = content,
                linkPreviews = emptyList(),
                group = null,
                isGroupUpdateMessage = false
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
        val userPublicKey = loginStateRepository.requireLocalNumber()
        val senderPublicKey = message.sender

        message.id = if (senderPublicKey == null || userPublicKey == senderPublicKey) {
            // sender is self or a linked device
            insertOutgoingExpirationTimerMessage(message)
        } else {
            insertIncomingExpirationTimerMessage(message)
        }
    }

    override fun onMessageSent(message: Message) {
        // When a message is sent, we'll schedule deletion immediately if we have an expiry mode,
        // even if the expiry mode is set to AfterRead, as we don't have a reliable way to know
        // that the recipient has read the message at at all. From our perspective it's better
        // to disappear the message regardlessly for the safety of ourselves.
        // As for the receiver, they will be able to disappear the message correctly after
        // they've done reading it.
        val messageId = message.id
        if (message.expiryMode != ExpiryMode.NONE && messageId != null) {
            getDatabase(messageId.mms)
                .markExpireStarted(messageId.id, clock.currentTimeMills())
        }
    }

    override fun onMessageReceived(message: Message) {
        val messageId = message.id ?: return

        // When we receive a message, we'll schedule deletion if it has an expiry mode set to
        // AfterSend, as the message would be considered sent from the sender's perspective.
        // If we receive a message that is sent from ourselves (aka the sync message), we
        // will start the expiry timer regardless
        if (message.expiryMode is ExpiryMode.AfterSend ||
            (message.expiryMode != ExpiryMode.NONE && message.isSenderSelf)
        ) {
            getDatabase(messageId.mms)
                .markExpireStarted(messageId.id, clock.currentTimeMills())
        }
    }

    private suspend fun processDatabase(db: MessagingDatabase) {
        while (true) {
            val expiredMessages = db.getExpiredMessageIDs(clock.currentTimeMills())

            if (expiredMessages.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Deleting ${expiredMessages.size} expired messages from ${db.javaClass.simpleName}"
                )
                for (messageId in expiredMessages) {
                    try {
                        db.deleteMessage(messageId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete expired message with ID $messageId", e)
                    }
                }
            }

            val nextExpiration = db.nextExpiringTimestamp
            val now = clock.currentTimeMills()

            if (nextExpiration > 0 && nextExpiration <= now) {
                continue // Proceed to the next iteration if the next expiration is already or about go to in the past
            }

            val dbChanges = threadDatabase.updateNotifications

            if (nextExpiration > 0) {
                val delayMills = nextExpiration - now
                Log.d(
                    TAG,
                    "Wait for up to $delayMills ms for next expiration in ${db.javaClass.simpleName}"
                )
                withTimeoutOrNull(delayMills) {
                    dbChanges.first()
                }
            } else {
                Log.d(
                    TAG,
                    "No next expiration found, waiting for any change in ${db.javaClass.simpleName}"
                )
                // If there are no next expiration, just wait for any change in the database
                dbChanges.first()
            }
        }
    }
}

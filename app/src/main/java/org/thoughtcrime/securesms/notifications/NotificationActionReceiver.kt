package org.thoughtcrime.securesms.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.messaging.messages.applyExpiryMode
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getOrCreateThreadIdFor
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.pro.ProStatusManager
import javax.inject.Inject

/**
 * A [BroadcastReceiver] that handles notification actions: marking a conversation as read,
 * or sending an inline reply from the notification shade.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var storage: Storage

    @Inject
    lateinit var threadDatabase: ThreadDatabase

    @Inject
    lateinit var smsDatabase: SmsDatabase

    @Inject
    lateinit var clock: SnodeClock

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var messageSender: MessageSender

    @Inject
    lateinit var proStatusManager: ProStatusManager

    @Inject
    @ManagerScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val handler: (suspend () -> Unit) = when (intent.action) {
            ACTION_MARK_READ -> ({ handleMarkRead(intent) })
            ACTION_REPLY -> ({ handleReply(intent) })
            else -> return
        }

        val result = goAsync()
        scope.launch {
            try {
                handler()
            } catch (e: Throwable) {
                Log.e(TAG, "Error handling notification action: ${intent.action}", e)
            } finally {
                result.finish()
            }
        }
    }

    private fun handleMarkRead(intent: Intent) {
        val threadAddress = requireNotNull(
            IntentCompat.getParcelableExtra(intent, EXTRA_THREAD_ADDRESS, Address.Conversable::class.java)
        ) { "Missing thread address" }

        val lastSeenTime = intent.getLongExtra(EXTRA_LATEST_MESSAGE_TIMESTAMP, 0L)
        storage.updateConversationLastSeenIfNeeded(threadAddress, lastSeenTime)
    }

    private fun handleReply(intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val threadAddress = requireNotNull(
            IntentCompat.getParcelableExtra(intent, EXTRA_THREAD_ADDRESS, Address.Conversable::class.java)
        ) { "Missing thread address" }
        val responseText = remoteInput.getCharSequence(EXTRA_REPLY_TEXT) ?: return

        val threadRecipient = recipientRepository.getRecipientSync(threadAddress)

        val message = VisibleMessage()
        message.sentTimestamp = clock.currentTimeMillis()
        message.text = responseText.toString()
        proStatusManager.addProFeatures(message)
        message.applyExpiryMode(threadRecipient)

        message.id = MessageId(
            smsDatabase.insertMessageOutbox(
                threadDatabase.getOrCreateThreadIdFor(threadAddress),
                OutgoingTextMessage(
                    message,
                    threadAddress,
                    threadRecipient.expiryMode.expiryMillis,
                    0L
                ),
                false,
                clock.currentTimeMillis()
            ),
            false
        )

        messageSender.send(message, threadAddress)

        storage.updateConversationLastSeenIfNeeded(
            threadAddress = threadAddress,
            lastSeenTime = clock.currentTimeMillis()
        )
    }

    companion object {
        private const val TAG = "NotificationActionReceiver"

        private const val ACTION_MARK_READ = "network.loki.securesms.notifications.MARK_READ"
        private const val ACTION_REPLY = "network.loki.securesms.notifications.REPLY"

        private const val EXTRA_THREAD_ADDRESS = "thread_address"
        private const val EXTRA_LATEST_MESSAGE_TIMESTAMP = "latest_timestamp"
        private const val EXTRA_REPLY_TEXT = "extra_reply_text"

        fun buildMarkReadIntent(
            context: Context,
            threadAddress: Address.Conversable,
            latestMessageTimestampMs: Long
        ): PendingIntent {
            return PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(ACTION_MARK_READ)
                    .putExtra(EXTRA_THREAD_ADDRESS, threadAddress)
                    .putExtra(EXTRA_LATEST_MESSAGE_TIMESTAMP, latestMessageTimestampMs),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun buildReplyIntent(
            context: Context,
            threadAddress: Address.Conversable
        ): Pair<PendingIntent, RemoteInput> {
            val remoteInput = RemoteInput.Builder(EXTRA_REPLY_TEXT)
                .setLabel(context.getString(R.string.reply))
                .build()

            val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra(EXTRA_THREAD_ADDRESS, threadAddress)
            }

            return PendingIntent.getBroadcast(
                context,
                2,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            ) to remoteInput
        }
    }
}

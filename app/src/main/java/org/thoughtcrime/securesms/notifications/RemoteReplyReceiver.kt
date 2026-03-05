package org.thoughtcrime.securesms.notifications

import android.annotation.SuppressLint
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
import org.thoughtcrime.securesms.database.MmsDatabase
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
 * Get the response text from the notification and sends an message as a reply
 */
@AndroidEntryPoint
class RemoteReplyReceiver : BroadcastReceiver() {
    @Inject
    lateinit var threadDatabase: ThreadDatabase

    @Inject
    lateinit var smsDatabase: SmsDatabase

    @Inject
    lateinit var storage: Storage

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

    @SuppressLint("StaticFieldLeak")
    override fun onReceive(context: Context, intent: Intent) {
        if (REPLY_ACTION != intent.action) return
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return

        val threadAddress = requireNotNull(
            IntentCompat.getParcelableExtra(
                intent,
                ADDRESS_EXTRA,
                Address.Conversable::class.java
            )
        ) {
            "Address must be specified"
        }

        val responseText = remoteInput.getCharSequence(REPLY_TEXT)

        if (responseText != null) {
            val r = goAsync()

            scope.launch {
                try {
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

                    // By sending a message, we'll update the last seen also
                    storage.updateConversationLastSeenIfNeeded(
                        threadAddress = threadAddress,
                        lastSeenTime = clock.currentTimeMillis()
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Error sending reply", e)
                } finally {
                    r.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG: String = "RemoteReplyReceiver"
        private const val REPLY_ACTION: String = "network.loki.securesms.notifications.WEAR_REPLY"
        private const val REPLY_TEXT: String = "extra_remote_reply"
        private const val ADDRESS_EXTRA: String = "address"

        fun buildIntent(
            context: Context,
            threadAddress: Address.Conversable
        ): Pair<PendingIntent, RemoteInput> {
            // Reply Action
            val remoteInput = RemoteInput.Builder(REPLY_TEXT)
                .setLabel(context.getString(R.string.reply))
                .build()

            val replyIntent = Intent(context, RemoteReplyReceiver::class.java).apply {
                action = REPLY_ACTION
                putExtra(ADDRESS_EXTRA, threadAddress)
            }

            return PendingIntent.getBroadcast(
                context,
                1,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            ) to remoteInput
        }
    }
}

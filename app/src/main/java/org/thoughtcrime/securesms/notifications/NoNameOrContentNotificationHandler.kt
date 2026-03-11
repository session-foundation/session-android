package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsignal.utilities.Log
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.merge
import network.loki.messenger.R
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getMessages
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.getLastSeen
import org.thoughtcrime.securesms.database.model.MessageChanges
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.ThreadChanges
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.notifications.ThreadBasedNotificationHandler.Companion.currentlyShowingConversation
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles notifications in [NotificationPrivacy.ShowNoNameOrContent] mode.
 *
 * Shows a single global notification ("You've got a new message.") whenever any thread has
 * unread messages, with no thread name or message content exposed. The notification is
 * suppressed when the home screen is in the foreground. Per-thread [NotifyType] filters
 * are still respected.
 */
@Singleton
class NoNameOrContentNotificationHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val recipientRepository: RecipientRepository,
    private val currentActivityObserver: CurrentActivityObserver,
    private val channels: NotificationChannelManager,
    private val notificationManager: NotificationManagerCompat
) {
    private val currentActivity get() = currentActivityObserver.currentActivity.value

    suspend fun process() {
        merge(
            threadDb.changeNotification,
            mmsDatabase.changeNotification,
            smsDatabase.changeNotification
        ).collect(object : FlowCollector<Any> {
            private var lastNotified: NotifyState? = null

            override suspend fun emit(value: Any) {
                when (value) {
                    is ThreadChanges -> {
                        if (lastNotified == null || !hasGlobalNotification()) {
                            Log.d(
                                TAG,
                                "ThreadChanges threadId=${value.id}: no active global notification — skipping"
                            )
                            return
                        }

                        // If we've got a new last seen time, and it's greater than currently notifying
                        // time of sent, dismiss the notification
                        val newLastSeen =
                            threadDb.getLastSeen(value.address)?.toEpochMilliseconds() ?: 0L
                        if (value.id == lastNotified!!.threadId &&
                            newLastSeen >= lastNotified!!.messageDateSentMs
                        ) {
                            Log.d(
                                TAG,
                                "ThreadChanges threadId=${value.id}: lastSeen=$newLastSeen >= notified ts=${lastNotified!!.messageDateSentMs} — cancelling global notification"
                            )
                            notificationManager.cancel(NotificationId.GLOBAL_MESSAGE)
                        }
                    }

                    is MessageChanges -> {
                        if (value.changeType != MessageChanges.ChangeType.Added) {
                            Log.d(
                                TAG,
                                "MessageChanges threadId=${value.threadId}: changeType=${value.changeType}, not Added — skipping"
                            )
                            return
                        }

                        val (address, lastSeen) = threadDb.getAddressAndLastSeen(value.threadId)
                            ?: run {
                                Log.d(
                                    TAG,
                                    "MessageChanges threadId=${value.threadId}: no address/lastSeen found — skipping"
                                )
                                return
                            }

                        val updateOnly = currentActivity is HomeActivity ||
                                currentActivityObserver.currentlyShowingConversation == address

                        if (updateOnly && !hasGlobalNotification()) {
                            Log.d(
                                TAG,
                                "MessageChanges threadId=${value.threadId}: updateOnly=true but no active global notification — skipping"
                            )
                            return
                        }

                        val recipient = recipientRepository.getRecipientSync(address)

                        if (recipient.blocked) {
                            Log.d(
                                TAG,
                                "MessageChanges threadId=${value.threadId}: recipient is blocked — skipping"
                            )
                            return
                        }

                        val notifyType = recipient.effectiveNotifyType()

                        if (notifyType == NotifyType.NONE) {
                            Log.d(
                                TAG,
                                "MessageChanges threadId=${value.threadId}: notifyType=NONE — skipping"
                            )
                            return
                        }

                        val latestMessageSentMs = mmsSmsDatabase.getMessages(value.ids)
                            .asSequence()
                            .filter { msg ->
                                !msg.isOutgoing &&
                                        !msg.isDeleted &&
                                        msg.dateSent > lastSeen &&
                                        (notifyType != NotifyType.MENTIONS || containsMentionOfMe(
                                            msg.body
                                        ))
                            }
                            .maxOfOrNull { it.dateSent }
                            ?: run {
                                Log.d(
                                    TAG,
                                    "MessageChanges threadId=${value.threadId}: no qualifying messages (notifyType=$notifyType) — skipping"
                                )
                                return
                            }

                        if (lastNotified != null && latestMessageSentMs <= lastNotified!!.messageDateSentMs) {
                            Log.d(
                                TAG,
                                "MessageChanges threadId=${value.threadId}: already notified for ts=$latestMessageSentMs — skipping"
                            )
                            return
                        }

                        lastNotified = NotifyState(value.threadId, latestMessageSentMs)
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Log.w(
                                TAG,
                                "MessageChanges threadId=${value.threadId}: POST_NOTIFICATIONS permission not granted — cannot post notification"
                            )
                            return
                        }

                        Log.d(
                            TAG,
                            "MessageChanges threadId=${value.threadId}: posting global notification (ts=$latestMessageSentMs, updateOnly=$updateOnly)"
                        )
                        notificationManager.notify(
                            NotificationId.GLOBAL_MESSAGE,
                            NotificationCompat.Builder(
                                context, channels.getNotificationChannelId(
                                    NotificationChannelManager.ChannelDescription.ONE_TO_ONE_MESSAGES
                                )
                            )
                                .setSmallIcon(R.drawable.ic_notification)
                                .setColor(
                                    ContextCompat.getColor(
                                        context,
                                        R.color.textsecure_primary
                                    )
                                )
                                .setContentText(
                                    context.resources.getQuantityString(
                                        R.plurals.messageNewYouveGot,
                                        1
                                    )
                                )
                                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                .setContentIntent(
                                    PendingIntent.getActivity(
                                        context,
                                        0,
                                        Intent(context, HomeActivity::class.java),
                                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                    )
                                )
                                .setAutoCancel(true)
                                .build()
                        )
                    }

                    else -> error("Unexpected value: $value")
                }
            }
        })
    }

    private fun hasGlobalNotification(): Boolean {
        return notificationManager.activeNotifications.any { it.id == NotificationId.GLOBAL_MESSAGE }
    }

    private fun containsMentionOfMe(input: CharSequence): Boolean {
        return MentionUtilities.parseMentions(input)
            ?.any { range ->
                recipientRepository.fastIsSelf(
                    input.substring(range.first + 1, range.last + 1).toAddress()
                )
            } == true
    }

    private data class NotifyState(
        val threadId: Long,
        val messageDateSentMs: Long
    )

    companion object {
        private const val TAG = "NoNameOrContentNotificationHandler"

    }
}

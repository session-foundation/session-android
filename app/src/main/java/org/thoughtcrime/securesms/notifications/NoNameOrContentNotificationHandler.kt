package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.collection.MutableLongLongMap
import androidx.collection.arraySetOf
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsignal.utilities.Log
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.merge
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities.mentionsMe
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getMessages
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.ThreadId
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.getAllLastSeen
import org.thoughtcrime.securesms.database.getLastSeen
import org.thoughtcrime.securesms.database.model.MessageChanges
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.ThreadChanges
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.notifications.ThreadBasedNotificationHandler.Companion.currentlyShowingConversation
import org.thoughtcrime.securesms.notifications.ThreadBasedNotificationHandler.Companion.getChannelIdFor
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.util.AppVisibilityManager
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
    private val notificationManager: NotificationManagerCompat,
    private val prefs: PreferenceStorage,
    private val appVisibilityManager: AppVisibilityManager,
) {
    private val currentActivity get() = currentActivityObserver.currentActivity.value

    suspend fun process() {
        merge(
            threadDb.changeNotification,
            mmsDatabase.changeNotification,
            smsDatabase.changeNotification
        ).collect(object : FlowCollector<Any> {
            // It's ok to have this as memory state - because we are listening only to db
            // changes - we won't be processing a message twice anyway
            private val notifiedMessages = arraySetOf<MessageId>()

            override suspend fun emit(value: Any) {
                when (value) {
                    is ThreadChanges -> {
                        val newLastSeen =
                            threadDb.getLastSeen(value.address)?.toEpochMilliseconds() ?: return

                        var hasActiveMessageNotification = false
                        // Remove message notifications where they have been marked as read
                        notificationManager
                            .activeNotifications
                            .forEach { msg ->
                                if (msg.notification.extras.getLong(MESSAGE_EXTRA_THREAD_ID) == value.id &&
                                    msg.notification.`when` <= newLastSeen
                                ) {
                                    notificationManager.cancel(msg.tag, msg.id)
                                } else if (msg.id == NotificationId.GLOBAL_MESSAGE) {
                                    hasActiveMessageNotification = true
                                }
                            }

                        if (!hasActiveMessageNotification) {
                            // If we don't have any messages left, also try to cancel the group summary
                            notificationManager.cancel(NotificationId.GLOBAL_MESSAGE_SUMMARY)
                        }
                    }

                    is MessageChanges if value.changeType == MessageChanges.ChangeType.Deleted -> {
                        // Delete all related notifications belong to the deleted messages
                        value.ids.forEach {
                            notificationManager.cancel(
                                messageTag(it),
                                NotificationId.GLOBAL_MESSAGE
                            )
                        }
                    }

                    is MessageChanges if value.changeType == MessageChanges.ChangeType.Added -> {
                        if (currentActivity is HomeActivity) {
                            // Don't bother notifying if we are on home screen
                            return
                        }

                        val (threadAddress, lastSeen) = threadDb.getAddressAndLastSeen(value.threadId)
                            ?: run {
                                Log.w(TAG, "MessageChanges threadId=${value.threadId}: no address/lastSeen found — skipping")
                                return
                            }

                        if (currentActivityObserver.currentlyShowingConversation == threadAddress) {
                            // Don't bother notifying if we are showing the conversation
                            return
                        }

                        val threadRecipient = recipientRepository.getRecipient(threadAddress)
                        val notifyType = threadRecipient.effectiveNotifyType()

                        // No notification if this thread is blocked or notification disabled
                        if (threadRecipient.blocked || notifyType == NotifyType.NONE) {
                            return
                        }

                        val messages = mmsSmsDatabase.getMessages(value.ids)
                            .filter { msg ->
                                !msg.isOutgoing && !msg.isDeleted && msg.dateSent > lastSeen &&
                                        msg.messageId !in notifiedMessages &&
                                        (notifyType != NotifyType.MENTIONS ||
                                                mentionsMe(msg.body, recipientRepository))
                            }

                        if (messages.isEmpty()) {
                            // Nothing to notify
                            return
                        }

                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            // No permission to show notifications
                            return
                        }

                        for (msg in messages) {
                            notifiedMessages.add(msg.messageId)

                            notificationManager.notify(
                                messageTag(msg.messageId),
                                NotificationId.GLOBAL_MESSAGE,
                                buildNotification(
                                    msg = msg,
                                    threadId = value.threadId,
                                    threadAddress = threadAddress
                                )
                            )
                        }

                        notificationManager.notify(
                            NotificationId.GLOBAL_MESSAGE_SUMMARY,
                            NotificationCompat.Builder(context, channels.getNotificationChannelId(
                                NotificationChannelManager.ChannelDescription.ONE_TO_ONE_MESSAGES))
                                .setSmallIcon(R.drawable.ic_notification)
                                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                .setGroup(NOTIFICATION_GROUP_NAME)
                                .setGroupSummary(true)
                                .setContentIntent(PendingIntent.getActivity(
                                    context,
                                    0,
                                    Intent(context, HomeActivity::class.java),
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                ))
                                .setSilent(!prefs[NotificationPreferences.SOUND_WHEN_APP_OPEN] && appVisibilityManager.isAppVisible.value)
                                .setAutoCancel(true)
                                .build()
                        )
                    }
                }
            }

            private fun buildNotification(
                msg: MessageRecord,
                threadId: ThreadId,
                threadAddress: Address.Conversable
            ): Notification {
                return NotificationCompat.Builder(context, channels.getChannelIdFor(threadAddress))
                    .setGroup(NOTIFICATION_GROUP_NAME)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.resources.getQuantityString(R.plurals.messageNewYouveGot, 1))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(PendingIntent.getActivity(
                        context,
                        threadId.hashCode(),
                        ConversationActivityV2.createIntent(context, threadAddress),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    ))
                    .setWhen(msg.dateSent)
                    .setExtras(Bundle(1).apply {
                        putLong(MESSAGE_EXTRA_THREAD_ID, threadId)
                    })
                    .setAutoCancel(true)
                    .build()

            }
        })
    }

    companion object {
        private const val TAG = "NoNameOrContentNotificationHandler"

        private const val NOTIFICATION_GROUP_NAME = "global_message_notification"

        private const val MESSAGE_EXTRA_THREAD_ID = "thread_id"

        private fun messageTag(id: MessageId): String {
            return "${id.id}-${id.mms}"
        }
    }
}

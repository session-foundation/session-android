package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.annotation.SuppressLint
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
import org.thoughtcrime.securesms.database.ReactionDatabase
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
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.database.model.ThreadChanges
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.notifications.ThreadBasedNotificationHandler.Companion.currentlyShowingConversation
import org.thoughtcrime.securesms.notifications.ThreadBasedNotificationHandler.Companion.getChannelIdFor
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

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
    threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val reactionDatabase: ReactionDatabase,
    recipientRepository: RecipientRepository,
    currentActivityObserver: CurrentActivityObserver,
    private val channels: NotificationChannelManager,
    private val notificationManager: NotificationManagerCompat,
    private val prefs: PreferenceStorage,
    private val appVisibilityManager: AppVisibilityManager,
): BaseNotificationHandler(
    currentActivityObserver = currentActivityObserver,
    threadDb = threadDb,
    recipientRepository = recipientRepository,
) {
    suspend fun process() {
        merge(
            threadDb.changeNotification,
            mmsDatabase.changeNotification,
            smsDatabase.changeNotification,
            reactionDatabase.changeNotification,
        ).collect(object : FlowCollector<Any> {
            private val lastNotifiedByThreadId = MutableLongLongMap()

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
                        val (threadAddress, threadLastSeen, notifyType) = getThreadDataIfEligibleForNotification(value.threadId) ?: return
                        val threadLastNotified = lastNotifiedByThreadId.getOrDefault(value.threadId, 0L)

                        val messages = mmsSmsDatabase.getMessages(value.ids)
                            .filter { msg ->
                                !msg.isOutgoing && !msg.isDeleted &&
                                        msg.dateSent > max(threadLastSeen, threadLastNotified) &&
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
                            notificationManager.notify(
                                messageTag(msg.messageId),
                                NotificationId.GLOBAL_MESSAGE,
                                buildNotification(
                                    messageTimestamp = msg.dateSent,
                                    threadId = value.threadId,
                                    threadAddress = threadAddress
                                )
                            )
                        }

                        lastNotifiedByThreadId[value.threadId] = messages.maxOf { it.dateSent }
                        notifyGroupSummary()
                    }

                    is MessageId -> {
                        // Reaction has changed...
                        val message = mmsSmsDatabase.getMessageById(value) ?: run {
                            Log.w(TAG, "Unable to get message for id=$value")
                            return
                        }

                        val (threadAddress, threadLastSeen, notifyType) = getThreadDataIfEligibleForNotification(message.threadId) ?: return
                        if (notifyType != NotifyType.ALL || threadAddress is Address.Community) {
                            // No need to notify
                            return
                        }

                        val threadLastNotified = lastNotifiedByThreadId.getOrDefault(message.threadId, 0L)

                        val newReactions = reactionDatabase.getReactionsForThread(
                            message.threadId,
                            max(threadLastSeen, threadLastNotified)
                        )

                        if (newReactions.isEmpty()) {
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

                        for (reaction in newReactions) {
                            notificationManager.notify(
                                reactionTag(reaction),
                                NotificationId.GLOBAL_MESSAGE,
                                buildNotification(
                                    messageTimestamp = reaction.dateSent,
                                    threadId = message.threadId,
                                    threadAddress = threadAddress
                                )
                            )
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            private fun notifyGroupSummary() {
                notificationManager.notify(
                    NotificationId.GLOBAL_MESSAGE_SUMMARY,
                    NotificationCompat.Builder(
                        context, channels.getNotificationChannelId(
                            NotificationChannelManager.ChannelDescription.ONE_TO_ONE_MESSAGES
                        )
                    )
                        .setSmallIcon(R.drawable.ic_notification)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setGroup(NOTIFICATION_GROUP_NAME)
                        .setGroupSummary(true)
                        .setContentIntent(
                            PendingIntent.getActivity(
                                context,
                                0,
                                Intent(context, HomeActivity::class.java),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        )
                        .setSilent(!prefs[NotificationPreferences.SOUND_WHEN_APP_OPEN] && appVisibilityManager.isAppVisible.value)
                        .setAutoCancel(true)
                        .build()
                )
            }

            private fun buildNotification(
                messageTimestamp: Long,
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
                    .setWhen(messageTimestamp)
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

        private fun reactionTag(reactionRecord: ReactionRecord): String {
            return "${messageTag(reactionRecord.messageId)}-reaction-${reactionRecord.id}"
        }
    }
}

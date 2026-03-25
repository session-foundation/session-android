package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.collection.MutableLongLongMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.merge
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getMessages
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getLastSeen
import org.thoughtcrime.securesms.database.model.MessageChanges
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.ThreadChanges
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Handles notifications in [NotificationPrivacy.ShowNameOnly] mode.
 *
 * Shows one per-thread notification with the thread name and avatar, but replaces the message
 * body with a generic "You've got a new message" string. No reaction notifications are shown.
 * The [NotifyType.MENTIONS] per-thread filter is still respected.
 */
@Singleton
class NameOnlyNotificationHandler @Inject constructor(
    @ApplicationContext context: Context,
    threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val reactionDb: ReactionDatabase,
    recipientRepository: RecipientRepository,
    currentActivityObserver: CurrentActivityObserver,
    avatarUtils: AvatarUtils,
    avatarBitmapCache: AvatarBitmapCache,
    channels: NotificationChannelManager,
    notificationManager: NotificationManagerCompat,
    prefs: PreferenceStorage,
    appVisibilityManager: AppVisibilityManager,
) : ThreadBasedNotificationHandler(
    context = context,
    currentActivityObserver = currentActivityObserver,
    avatarUtils = avatarUtils,
    channels = channels,
    recipientRepository = recipientRepository,
    avatarBitmapCache = avatarBitmapCache,
    notificationManager = notificationManager,
    prefs = prefs,
    appVisibilityManager = appVisibilityManager,
    threadDatabase = threadDb,
) {
    suspend fun process() {
        merge(
            threadDb.changeNotification,
            mmsDatabase.changeNotification,
            smsDatabase.changeNotification,
            reactionDb.changeNotification,
        ).collect(object : FlowCollector<Any> {
            private val lastNotifiedMessageTimestamp = MutableLongLongMap()

            override suspend fun emit(value: Any) {
                when (value) {
                    is ThreadChanges -> {
                        val newLastSeen = threadDb.getLastSeen(value.address)?.toEpochMilliseconds()
                            ?: return

                        // Cancel the thread notification if the whole thread is read
                        getActiveThreadNotification(value.id)
                            ?.takeIf { it.notification.`when` <= newLastSeen }
                            ?.let { notificationManager.cancel(it.tag, it.id) }

                        return
                    }

                    is MessageChanges if value.changeType == MessageChanges.ChangeType.Added -> {
                        val (threadAddress, lastSeen, threadNotifyType, threadRecipient) =
                            getThreadDataIfEligibleForNotification(value.threadId) ?: return

                        val latestMessage = mmsSmsDatabase.getMessages(value.ids)
                            .filter { msg ->
                                !msg.isOutgoing && !msg.isDeleted && msg.dateSent > lastSeen &&
                                        (threadNotifyType != NotifyType.MENTIONS ||
                                                MentionUtilities.mentionsMe(msg.body, recipientRepository))
                            }
                            .maxByOrNull { it.dateSent }

                        if (latestMessage == null) return

                        val lastNotified =
                            lastNotifiedMessageTimestamp.getOrDefault(value.threadId, 0L)
                        if (latestMessage.dateSent <= lastNotified) return

                        lastNotifiedMessageTimestamp.put(value.threadId, latestMessage.dateSent)

                        postOrUpdateNotification(
                            threadAddress = threadAddress,
                            threadRecipient = threadRecipient,
                            threadId = value.threadId,
                            messages = listOf(NotificationCompat.MessagingStyle.Message(
                                context.resources.getQuantityText(R.plurals.messageNew, 1),
                                latestMessage.dateSent,
                                latestMessage.toPerson(null),
                            )),
                            canReply = false,
                            silent = false,
                        )
                    }

                    is MessageId -> {
                        // Received reaction updates
                        val message = mmsSmsDatabase.getMessageById(value) ?: run {
                            Log.w(TAG, "Unable to get message for id=$value")
                            return
                        }

                        val (threadAddress, lastSeen, threadNotifyType, threadRecipient) =
                            getThreadDataIfEligibleForNotification(message.threadId) ?: return

                        // Community's reaction is not notified
                        if (threadAddress is Address.Community) return

                        // Only notify reaction when notify type is ALL
                        if (threadNotifyType != NotifyType.ALL) return

                        val reactions = reactionDb.getReactionsForThread(
                            threadId = message.threadId,
                            minSendTimeMsExclusive = max(
                                lastSeen,
                                lastNotifiedMessageTimestamp.getOrDefault(message.threadId, 0L)
                            )
                        )

                        if (reactions.isEmpty()) {
                            // Nothing new
                            return
                        }

                        val notified = reactions.maxBy { it.dateSent }
                        lastNotifiedMessageTimestamp.put(message.threadId, notified.dateSent)

                        postOrUpdateNotification(
                            threadAddress = threadAddress,
                            threadRecipient = threadRecipient,
                            threadId = message.threadId,
                            messages = listOf(NotificationCompat.MessagingStyle.Message(
                                context.resources.getQuantityText(R.plurals.messageNew, 1),
                                notified.dateSent,
                                notified.toPerson(null),
                            )),
                            canReply = false,
                            silent = false,
                        )
                    }
                }

            }
        })
    }

    companion object {
        private const val TAG = "NameOnlyNotificationHandler"
    }
}

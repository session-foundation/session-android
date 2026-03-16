package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.merge
import network.loki.messenger.R
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.messages.MessageFormatter
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getMessages
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.getLastSeen
import org.thoughtcrime.securesms.database.model.MessageChanges
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.ThreadChanges
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton

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
    private val threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
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
) {
    suspend fun process() {
        merge(
            threadDb.changeNotification,
            mmsDatabase.changeNotification,
            smsDatabase.changeNotification
        ).collect { event ->
            when (event) {
                is ThreadChanges -> {
                    val newLastSeen = threadDb.getLastSeen(event.address)?.toEpochMilliseconds()
                        ?: return@collect

                    // Cancel the thread notification if the whole thread is read
                    getActiveThreadNotification(event.id)
                        ?.takeIf { it.notification.`when` <= newLastSeen }
                        ?.let { notificationManager.cancel(it.tag, it.id) }
                }

                is MessageChanges if event.changeType == MessageChanges.ChangeType.Added -> {
                    if (currentActivity is HomeActivity) return@collect

                    val (threadAddress, lastSeen) = threadDb.getAddressAndLastSeen(event.threadId) ?: run {
                        Log.w(TAG, "Unable to get address for threadId=${event.threadId}")
                        return@collect
                    }

                    if (currentActivityObserver.currentlyShowingConversation == threadAddress) {
                        return@collect
                    }

                    val threadRecipient = recipientRepository.getRecipientSync(threadAddress)
                    if (threadRecipient.blocked) return@collect

                    val threadNotifyType = threadRecipient.effectiveNotifyType()
                    if (threadNotifyType == NotifyType.NONE) return@collect

                    val latestMessage = mmsSmsDatabase.getMessages(event.ids)
                        .filter { msg ->
                            !msg.isOutgoing && !msg.isDeleted && msg.dateSent > lastSeen &&
                                    (threadNotifyType != NotifyType.MENTIONS ||
                                            MentionUtilities.mentionsMe(msg.body, recipientRepository))
                        }
                        .maxByOrNull { it.dateSent }

                    if (latestMessage == null) return@collect

                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return@collect
                    }

                    postOrUpdateNotification(
                        threadAddress = threadAddress,
                        threadRecipient = threadRecipient,
                        threadId = event.threadId,
                        latestMessageTimestampMs = latestMessage.dateSent,
                        messages = listOf(NotificationCompat.MessagingStyle.Message(
                            context.resources.getQuantityText(R.plurals.messageNew, 1),
                            latestMessage.dateSent,
                            latestMessage.toPerson(null),
                        )),
                        canReply = false,
                        silent = false,
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "NameOnlyNotificationHandler"
    }
}

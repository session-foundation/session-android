package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.collection.MutableLongObjectMap
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.isCommunity
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.messages.MessageFormatter
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getIncomingMessages
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getThreadId
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.ui.components.OffscreenAvatarRenderer
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import androidx.core.graphics.createBitmap
import com.squareup.phrase.Phrase
import org.session.libsession.utilities.StringSubstitutionConstants.EMOJI_KEY

/**
 * Reactive notification processor that replaces the poll-based DefaultMessageNotifier.
 *
 * Follows the [MarkReadProcessor] pattern: an [AuthAwareComponent] that merges thread-update
 * and message-added flows, using [scan] to track `lastSeen` per thread.
 *
 * Key behaviours:
 * - A message is "new" purely if `dateSent > thread.lastSeen`
 * - Dismissing a notification does NOT mark the thread as read
 * - "Mark Read" sets `lastSeen` to the latest message's `dateSent`
 * - When `lastSeen` advances, stale notifications auto-cancel
 *
 * Notification building and posting is delegated to [NotificationPoster].
 */
@Singleton
class NotificationProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val recipientRepository: RecipientRepository,
    private val loginStateRepository: LoginStateRepository,
    private val currentActivityObserver: CurrentActivityObserver,
    private val prefs: PreferenceStorage,
    private val reactionDatabase: ReactionDatabase,
    private val messageFormatter: MessageFormatter,
    private val avatarUtils: AvatarUtils,
    private val offscreenAvatarRenderer: Provider<OffscreenAvatarRenderer>,
    private val notificationChannels: NotificationChannels,
) : AuthAwareComponent {
    private val currentActivity: Activity? get() = currentActivityObserver.currentActivity.value

    private val notificationManager: NotificationManagerCompat get() =
        NotificationManagerCompat.from(context)

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        merge(
            // Thread changes
            threadDb.updateNotifications.map { Changes(it, false) },

            // Message changes
            merge(mmsDatabase.changeNotification, smsDatabase.changeNotification)
                .map { Changes(it.threadId, false) },

            // Reactions changes
            reactionDatabase.changeNotification.mapNotNull { msgId ->
                mmsSmsDatabase.getThreadId(msgId)?.let { Changes(it, true) }
            }
        ).mapNotNull { changes ->
            // Do nothing if we are on home screen
            if (currentActivity is HomeActivity) return@mapNotNull null

            // Do nothing if overall settings is off
            if (!prefs[NotificationPreferences.ENABLE]) return@mapNotNull null

            val (threadAddress, lastSeen) = threadDb.getAddressAndLastSeen(changes.threadId)
                ?: return@mapNotNull null

            if (threadAddress.isCommunity && changes.fromReaction) {
                // We don't care about reaction changes from communities
                return@mapNotNull null
            }

            // Do nothing if this conversation is in the foreground
            if ((currentActivity as? ConversationActivityV2)?.threadAddress == threadAddress) {
                return@mapNotNull null
            }

            val recipient = recipientRepository.getRecipientSync(threadAddress)

            // Do nothing if recipient is muted
            if (recipient.notifyType == NotifyType.NONE) return@mapNotNull null

            val items = mutableListOf<NotificationMessageItem>()

            mmsSmsDatabase.getIncomingMessages(changes.threadId, startMsExclusive = lastSeen)
                .forEach { r ->
                    val body = MentionUtilities.highlightMentions(
                        recipientRepository = recipientRepository,
                        text = messageFormatter.formatMessageBody(context, r, recipient),
                        isOutgoingMessage = r.isOutgoing,
                        formatOnly = true, // No colors for notification
                        isQuote = false,
                        context = context,
                    )

                    if (recipient.notifyType == NotifyType.MENTIONS
                        && !body.mentions.any { it.second.isSelf }) {
                        // The message doesn't mention us in MENTIONS only mode.
                        // Ignoring...
                        return@forEach
                    }

                    items += MessageData(
                        id = r.messageId,
                        body = body.text,
                        sentAt = Instant.ofEpochMilli(r.dateSent),
                        authorName = r.individualRecipient.displayName(),
                        authorAvatar = avatarUtils.getUIDataFromRecipient(r.individualRecipient),
                        authorAddress = r.individualRecipient.address
                    )
                }

            // No reaction handling for communities
            if (!threadAddress.isCommunity && recipient.notifyType == NotifyType.ALL) {
                reactionDatabase.getReactionsForThread(
                    threadId = changes.threadId,
                    minSendTimeMsExclusive = lastSeen
                ).forEach { record ->
                    val r = recipientRepository.getRecipientSync(record.author.toAddress())
                    // Ignore reactions from self
                    if (r.isSelf) return@forEach

                    items += ReactionData(
                        reactionId = record.id,
                        emoji = record.emoji,
                        authorName = r.displayName(),
                        authorAvatar = avatarUtils.getUIDataFromRecipient(r),
                        sentAt = Instant.ofEpochMilli(record.dateSent),
                        authorAddress = r.address,
                    )
                }
            }

            if (items.isEmpty()) {
                ThreadNotificationState.Empty(changes.threadId)
            } else {
                items.sortBy { it.sentAt }

                ThreadNotificationState.Visible(
                    threadId = changes.threadId,
                    threadAddress = threadAddress,
                    threadName = recipient.displayName(),
                    threadAvatar = avatarUtils.getUIDataFromRecipient(recipient),
                    items = items,
                )
            }
        }.catch { e ->
            Log.e(TAG, "Error in notification processor", e)
        }.collect(object : FlowCollector<ThreadNotificationState> {
            private val lastPostedStateByThreadIDs = MutableLongObjectMap<ThreadNotificationState.Visible>()

            override suspend fun emit(value: ThreadNotificationState) {

                Log.d(TAG, "New state: $value")

                when (value) {
                    is ThreadNotificationState.Empty -> {
                        // Must dismiss the notification
                        notificationManager.cancel(threadTag(value.threadId),
                            NotificationId.MESSAGE_THREAD
                        )
                        lastPostedStateByThreadIDs.remove(value.threadId)
                    }

                    is ThreadNotificationState.Visible -> {
                        val existingNotification = notificationManager.activeNotifications
                            .firstOrNull { it.id == NotificationId.MESSAGE_THREAD &&
                                    it.tag == threadTag(value.threadId) }

                        if (existingNotification != null) {
                            updateNotification(existingNotification, value)
                            lastPostedStateByThreadIDs.put(value.threadId, value)
                        } else if (value.shouldPostNewNotification(lastPostedStateByThreadIDs[value.threadId])) {
                            postNewNotification(value)
                            lastPostedStateByThreadIDs.put(value.threadId, value)
                        }
                    }
                }
            }
        })
    }

    private suspend fun updateNotification(
        existingNotification: StatusBarNotification,
        value: ThreadNotificationState.Visible
    ) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        notificationManager.notify(threadTag(value.threadId), NotificationId.MESSAGE_THREAD,
            buildNotification(value, silent = true))
    }

    private suspend fun postNewNotification(value: ThreadNotificationState.Visible) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = buildNotification(value, silent = false)
        notificationManager.notify(threadTag(value.threadId), NotificationId.MESSAGE_THREAD, notification)
    }

    private suspend fun buildNotification(state: ThreadNotificationState.Visible, silent: Boolean): Notification {
        val channelId = notificationChannels.messagesChannel
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.textsecure_primary))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(state.threadAddress.toString())
            .setLocusId(LocusIdCompat(state.threadAddress.toString()))
            .setOnlyAlertOnce(silent)
            .setAutoCancel(true)

        // MessagingStyle
        val userPerson = Person.Builder()
            .setName(context.getString(R.string.you))
            .build()

        val style = NotificationCompat.MessagingStyle(userPerson)
            .setConversationTitle(state.threadName)
            .setGroupConversation(state.threadAddress is Address.GroupLike)


        // Cache for persons to avoid re-rendering avatars
        val persons = mutableMapOf<Address, Person>()

        for (item in state.items) {
            val person = persons.getOrPut(item.authorAddress) {
                Person.Builder()
                    .setName(item.authorName)
                    .setIcon(getIcon(item.authorAvatar))
                    .build()
            }
            style.addMessage(item.body(context), item.sentAt.toEpochMilli(), person)
        }

        builder.setStyle(style)

        // Large icon
        builder.setLargeIcon(getBitmap(state.threadAvatar))

        // Content Intent
        val intent = ConversationActivityV2.createIntent(context, state.threadAddress)
        val pendingIntent = PendingIntent.getActivity(
            context,
            state.threadId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)

        // Mark Read Action
        val markReadIntent = Intent(context, MarkReadReceiver::class.java).apply {
            action = MarkReadReceiver.CLEAR_ACTION
            putExtra(MarkReadReceiver.THREAD_IDS_EXTRA, longArrayOf(state.threadId))
            putExtra(MarkReadReceiver.LATEST_TIMESTAMP_EXTRA, state.items.last().sentAt.toEpochMilli())
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            state.threadId.toInt(),
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            context.getString(R.string.messageMarkRead),
            markReadPendingIntent
        ).build()
        builder.addAction(markReadAction)

        return builder.build()
    }

    private suspend fun getIcon(avatarUIData: AvatarUIData): IconCompat {
        return IconCompat.createWithBitmap(getBitmap(avatarUIData))
    }

    private suspend fun getBitmap(avatarUIData: AvatarUIData): Bitmap {
        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
        offscreenAvatarRenderer.get().render(bitmap, avatarUIData)
        return bitmap
    }


    // ── Data classes ──

    private data class Changes(
        val threadId: Long,
        val fromReaction: Boolean,
    )

    private sealed interface NotificationMessageItem {
        val sentAt: Instant
        val authorName: String
        val authorAvatar: AvatarUIData
        val authorAddress: Address

        fun body(context: Context): CharSequence
    }

    private data class MessageData(
        val id: MessageId,
        val body: CharSequence,
        override val sentAt: Instant,
        override val authorAddress: Address,
        override val authorName: String,
        override val authorAvatar: AvatarUIData,
    ) : NotificationMessageItem {
        override fun body(context: Context): CharSequence {
            return body
        }
    }

    private data class ReactionData(
        val reactionId: Long,
        val emoji: String,
        override val authorAddress: Address,
        override val authorName: String,
        override val authorAvatar: AvatarUIData,
        override val sentAt: Instant,
    ) : NotificationMessageItem {
        override fun body(context: Context): CharSequence {
            return Phrase.from(context, R.string.emojiReactsNotification)
                .put(EMOJI_KEY, emoji)
                .format()
        }
    }

    private sealed interface ThreadNotificationState {
        val threadId: Long

        data class Empty(override val threadId: Long) : ThreadNotificationState

        data class Visible(
            override val threadId: Long,
            val threadAddress: Address.Conversable,
            val threadName: String,
            val threadAvatar: AvatarUIData,
            val items: List<NotificationMessageItem>, // Must be sorted by sentAt
        ): ThreadNotificationState {
            init {
                require(items.isNotEmpty()) {
                    "At least one message item is required"
                }
            }


            /**
             * Return true when there is NO notification on the system for this thread AND this state
             * should result in creating a new notification.
             *
             * If a notification exists for this thread, you should always update it to reflect the
             * latest data.
             */
            fun shouldPostNewNotification(prevState: Visible?): Boolean {
                return prevState == null ||
                        items.last().sentAt > prevState.items.last().sentAt
            }
        }
    }


    private val ConversationActivityV2.threadAddress: Address.Conversable?
        get() = IntentCompat.getParcelableExtra(
            intent,
            ConversationActivityV2.ADDRESS,
            Address.Conversable::class.java
        )

    companion object {
        private const val TAG = "NotificationProcessor"

        const val EXTRA_REMOTE_REPLY = "extra_remote_reply"

        private fun threadTag(threadId: Long): String {
            return "thread-$threadId"
        }

        private fun threadIdFromTag(tag: String): Long? {
            return tag.removePrefix("thread-").toLongOrNull()
        }
    }
}

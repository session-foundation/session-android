package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.collection.LruCache
import androidx.collection.MutableLongObjectMap
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import com.squareup.phrase.Phrase
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
import org.session.libsession.utilities.StringSubstitutionConstants.EMOJI_KEY
import org.session.libsession.utilities.isCommunity
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
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
import org.thoughtcrime.securesms.ui.components.OffscreenAvatarRenderer
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

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
 */
@Singleton
class NotificationProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val recipientRepository: RecipientRepository,
    private val currentActivityObserver: CurrentActivityObserver,
    private val reactionDatabase: ReactionDatabase,
    private val messageFormatter: MessageFormatter,
    private val avatarUtils: AvatarUtils,
    private val offscreenAvatarRenderer: Provider<OffscreenAvatarRenderer>,
    private val channels: NotificationChannelManager,
) : AuthAwareComponent {
    private val currentActivity: Activity? get() = currentActivityObserver.currentActivity.value

    private val notificationManager: NotificationManagerCompat get() =
        NotificationManagerCompat.from(context)

    private val avatarBitmapCache = object : LruCache<AvatarUIData, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: AvatarUIData, value: Bitmap): Int = value.allocationByteCount
    }

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
        ).map { changes ->
            // Show nothing
            if (!notificationManager.areNotificationsEnabled())
                return@map ThreadNotificationState.Empty(changes.threadId)

            val (threadAddress, lastSeen) = threadDb.getAddressAndLastSeen(changes.threadId)
                ?: return@map ThreadNotificationState.Empty(changes.threadId)

            val threadRecipient = recipientRepository.getRecipientSync(threadAddress)

            // Do nothing if recipient is muted or blocked
            if (threadRecipient.notifyType == NotifyType.NONE || threadRecipient.blocked)
                return@map ThreadNotificationState.Empty(changes.threadId)

            val items = mutableListOf<NotificationMessageItem>()

            val newIncomingMessages =
                mmsSmsDatabase.getIncomingMessages(changes.threadId, startMsExclusive = lastSeen)

            if (!threadRecipient.approved) {
                // If this is a message request thread, only show a notification when we
                // receive a new message
                val lastMessage = newIncomingMessages.lastOrNull()
                    ?: return@map ThreadNotificationState.Empty(changes.threadId)

                items += MessageRequestData(
                    sentAt = Instant.ofEpochMilli(lastMessage.dateSent),
                    authorName = lastMessage.individualRecipient.displayName(),
                    authorAvatar = avatarUtils.getUIDataFromRecipient(lastMessage.individualRecipient),
                    authorAddress = lastMessage.individualRecipient.address
                )
            } else {
                newIncomingMessages
                    .forEach { r ->
                        val parsedResult = MentionUtilities.parseAndSubstituteMentions(
                            recipientRepository = recipientRepository,
                            input = messageFormatter.formatMessageBodyForNotification(
                                context,
                                r,
                                threadRecipient
                            ),
                            context = context,
                        )

                        if (threadRecipient.notifyType == NotifyType.MENTIONS &&
                            !parsedResult.mentions.any { it.isSelf }
                        ) {
                            // Skip this message as it doesn't contain any mentions of us
                            return@forEach
                        }

                        items += MessageData(
                            id = r.messageId,
                            body = parsedResult.text,
                            sentAt = Instant.ofEpochMilli(r.dateSent),
                            authorName = r.individualRecipient.displayName(),
                            authorAvatar = avatarUtils.getUIDataFromRecipient(r.individualRecipient),
                            authorAddress = r.individualRecipient.address
                        )
                    }

                // No reaction handling for communities
                if (!threadAddress.isCommunity && threadRecipient.notifyType == NotifyType.ALL) {
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
            }

            if (items.isEmpty()) {
                ThreadNotificationState.Empty(changes.threadId)
            } else {
                items.sortBy { it.sentAt }

                ThreadNotificationState.Visible(
                    threadId = changes.threadId,
                    threadAddress = threadAddress,
                    threadName = threadRecipient.displayName(),
                    threadAvatar = avatarUtils.getUIDataFromRecipient(threadRecipient),
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
                        // Logic for showing notification:
                        // 1. If a notification exists for this thread: update the notification
                        //    contents, then depends on the screens we are on:
                        //    a. if at home or this thread, silent update only.
                        //    b. otherwise, notify loudly when a new message(or reaction) came through
                        //
                        // 2. If a notification does not exist, we'll create a notification when:
                        //    a. Current screen is not home or this thread
                        //    We will loudly notify user when the notification is created in this
                        //    case.

                        val hasExistingNotification = notificationManager.activeNotifications
                            .any { it.id == NotificationId.MESSAGE_THREAD &&
                                    it.tag == threadTag(value.threadId) }

                        val convoInForeground = when (val a = currentActivity) {
                            is ConversationActivityV2 -> a.threadAddress == value.threadAddress
                            is HomeActivity -> true
                            else -> false
                        }

                        val shouldPostNewNotification = !convoInForeground &&
                                value.shouldPostNewNotification(lastPostedStateByThreadIDs[value.threadId])

                        if (hasExistingNotification) {
                            postOrUpdateNotification(value, !shouldPostNewNotification)
                            lastPostedStateByThreadIDs.put(value.threadId, value)
                        } else if (shouldPostNewNotification) {
                            postOrUpdateNotification(value, false)
                            lastPostedStateByThreadIDs.put(value.threadId, value)
                        }
                    }
                }
            }
        })
    }

    private suspend fun postOrUpdateNotification(
        state: ThreadNotificationState.Visible,
        silent: Boolean
    ) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        notificationManager.notify(threadTag(state.threadId), NotificationId.MESSAGE_THREAD,
            buildNotification(state, silent = silent))
    }


    private suspend fun buildNotification(state: ThreadNotificationState.Visible, silent: Boolean): Notification {
        val channelDesc = when (state.threadAddress) {
            is Address.Community -> NotificationChannelManager.ChannelDescription.COMMUNITY_MESSAGES
            is Address.Group,
            is Address.LegacyGroup -> NotificationChannelManager.ChannelDescription.GROUP_MESSAGES
            is Address.CommunityBlindedId,
            is Address.Standard -> NotificationChannelManager.ChannelDescription.ONE_TO_ONE_MESSAGES
        }

        val builder = NotificationCompat.Builder(context, channels.getNotificationChannelId(channelDesc))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.textsecure_primary))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(silent)
            .setAutoCancel(true)

        val userPerson = Person.Builder()
            .setName(context.getString(R.string.you))
            .setIcon(getIcon(avatarUtils.getUIDataFromRecipient(recipientRepository.getSelf())))
            .build()

        val style = NotificationCompat.MessagingStyle(userPerson)
            .setConversationTitle(state.threadName.takeIf { state.threadAddress is Address.GroupLike })
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
            state.threadAddress.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)

        // Mark Read Action
        builder.addAction(NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            context.getString(R.string.messageMarkRead),
            NotificationActionReceiver.buildMarkReadIntent(
                context = context,
                threadAddress = state.threadAddress,
                latestMessageTimestampMs = state.items.last().sentAt.toEpochMilli()
            )
        ).build())

        // Reply action (not applicable for message request thread)
        if (!state.items.any { it is MessageRequestData }) {
            val (replyIntent, remoteInput) = NotificationActionReceiver.buildReplyIntent(
                context = context,
                threadAddress = state.threadAddress
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_reply,
                    context.getString(R.string.reply),
                    replyIntent
                ).addRemoteInput(remoteInput)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build()
            )
        }

        return builder.build()
    }

    private suspend fun getIcon(avatarUIData: AvatarUIData): IconCompat {
        return IconCompat.createWithBitmap(getBitmap(avatarUIData))
    }

    private suspend fun getBitmap(avatarUIData: AvatarUIData): Bitmap {
        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val cached = avatarBitmapCache[avatarUIData]
        if (cached != null) {
            return cached
        }

        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        offscreenAvatarRenderer.get().render(bitmap, avatarUIData)
        avatarBitmapCache.put(avatarUIData, bitmap)
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

    private data class MessageRequestData(
        override val sentAt: Instant,
        override val authorName: String,
        override val authorAvatar: AvatarUIData,
        override val authorAddress: Address
    ) : NotificationMessageItem {
        override fun body(context: Context): CharSequence {
            return context.getString(R.string.messageRequestsNew)
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


        private fun threadTag(threadId: Long): String {
            return "thread-$threadId"
        }

        private fun threadIdFromTag(tag: String): Long? {
            return tag.removePrefix("thread-").toLongOrNull()
        }
    }
}

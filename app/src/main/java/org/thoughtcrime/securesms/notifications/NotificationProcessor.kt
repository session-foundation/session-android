package org.thoughtcrime.securesms.notifications

import android.app.Activity
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.collection.MutableLongObjectMap
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
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

            val messages = mmsSmsDatabase.getIncomingMessages(changes.threadId, startMsExclusive = lastSeen)
                .mapNotNull { r ->
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
                        return@mapNotNull null
                    }

                    MessageData(
                        id = r.messageId,
                        body = body.text,
                        sentAt = Instant.ofEpochMilli(r.dateSent),
                        authorName = r.individualRecipient.displayName(),
                        authorAvatar = avatarUtils.getUIDataFromRecipient(r.individualRecipient),
                    )
                }

            // No reaction handling for communities
            val incomingReactionWithRecipient = if (!threadAddress.isCommunity &&
                recipient.notifyType == NotifyType.ALL) {
                reactionDatabase.getReactionsForThread(
                    threadId = changes.threadId,
                    minSendTimeMsExclusive = lastSeen
                ).mapNotNull { record ->
                    val r = recipientRepository.getRecipientSync(record.author.toAddress())
                    // Ignore reactions from self
                    if (r.isSelf) return@mapNotNull null

                    ReactionData(
                        reactionId = record.id,
                        emoji = record.emoji,
                        authorName = r.displayName(),
                        authorAvatar = avatarUtils.getUIDataFromRecipient(r),
                        sentAt = Instant.ofEpochMilli(record.dateSent),
                    )
                }
            } else {
                emptyList()
            }

            if (messages.isEmpty() && incomingReactionWithRecipient.isEmpty()) {
                ThreadNotificationState.Empty(changes.threadId)
            } else {
                ThreadNotificationState.Visible(
                    threadId = changes.threadId,
                    threadAddress = threadAddress,
                    threadName = recipient.displayName(),
                    threadAvatar = avatarUtils.getUIDataFromRecipient(recipient),
                    messages = messages,
                    reactions = incomingReactionWithRecipient
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

    private fun updateNotification(
        existingNotification: StatusBarNotification,
        value: ThreadNotificationState.Visible
    ) {
        TODO("Not yet implemented")
    }

    private fun postNewNotification(value: ThreadNotificationState.Visible) {
        TODO("Not yet implemented")
    }

    // ── Data classes ──

    private data class Changes(
        val threadId: Long,
        val fromReaction: Boolean,
    )

    private data class MessageData(
        val id: MessageId,
        val body: CharSequence,
        val sentAt: Instant,
        val authorName: String,
        val authorAvatar: AvatarUIData,
    )

    private data class ReactionData(
        val reactionId: Long,
        val emoji: String,
        val authorName: String,
        val authorAvatar: AvatarUIData,
        val sentAt: Instant,
    )

    private sealed interface ThreadNotificationState {
        val threadId: Long

        data class Empty(override val threadId: Long) : ThreadNotificationState

        data class Visible(
            override val threadId: Long,
            val threadAddress: Address.Conversable,
            val threadName: String,
            val threadAvatar: AvatarUIData,
            val messages: List<MessageData>,
            val reactions: List<ReactionData>,
        ): ThreadNotificationState {
            init {
                require(messages.isNotEmpty() || reactions.isNotEmpty()) {
                    "At least one message or reaction is required for a visible state"
                }
            }

            private val lastMessageOrReactionSendDate: Instant get() {
                val lastMessageSent = messages.lastOrNull()?.sentAt
                val lastReactionSent = reactions.lastOrNull()?.sentAt

                return when {
                    lastMessageSent != null && lastReactionSent != null -> {
                        maxOf(lastMessageSent, lastReactionSent)
                    }

                    lastMessageSent != null -> lastMessageSent
                    else -> lastReactionSent!!
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
                        lastMessageOrReactionSendDate > prevState.lastMessageOrReactionSendDate
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

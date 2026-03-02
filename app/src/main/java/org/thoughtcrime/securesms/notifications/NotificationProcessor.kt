package org.thoughtcrime.securesms.notifications

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import androidx.core.content.IntentCompat
import androidx.collection.LongLongMap
import androidx.collection.MutableLongLongMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil3.ImageLoader
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.supervisorScope
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.NotificationPrivacyPreference
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.messages.MessageFormatter
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities.highlightMentions
import org.thoughtcrime.securesms.database.MessageChanges
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getMessages
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.getAllLastSeen
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.threadContainsOutgoingMessage
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.preferences.AppPreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import org.thoughtcrime.securesms.util.SessionMetaProtocol.canUserReplyToNotification
import org.thoughtcrime.securesms.util.SpanUtil
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Reactive notification processor that replaces the poll-based DefaultMessageNotifier.
 *
 * Follows the [MarkReadProcessor] pattern: an [AuthAwareComponent] that merges thread-update
 * and message-added flows, using [scan] to track `lastSeen` per thread. Notifications are
 * posted once per new message event, and auto-cancelled when `lastSeen` advances.
 *
 * Key behaviours:
 * - A message is "new" purely if `dateSent > thread.lastSeen`
 * - Dismissing a notification does NOT mark the thread as read
 * - "Mark Read" sets `lastSeen` to the latest message's `dateSent`
 * - When `lastSeen` advances, stale notifications auto-cancel
 *
 * Notification ID management:
 * Each thread gets a unique notification ID computed as [THREAD_NOTIFICATION_ID_BASE] + threadId.
 * The summary uses [SUMMARY_NOTIFICATION_ID]. All are also tagged with [TAG_PREFIX] + threadId
 * (or [TAG_SUMMARY] for the summary) so we can enumerate our own active notifications.
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
    private val messageFormatter: Lazy<MessageFormatter>,
    private val avatarUtils: AvatarUtils,
    private val imageLoader: Provider<ImageLoader>,
    private val currentActivityObserver: CurrentActivityObserver,
    private val prefs: PreferenceStorage,
) : AuthAwareComponent {

    val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState): Unit = supervisorScope {
        val threadLastSeenFlow: SharedFlow<ThreadUpdated> = threadDb.updateNotifications
            .map { id ->
                threadDb.getAddressAndLastSeen(id)?.let { (address, lastSeen) ->
                    ThreadUpdated(id, address, lastSeen)
                }
            }
            .filterNotNull()
            .distinctUntilChanged()
            .shareIn(this, SharingStarted.Lazily)

        val messageAddedFlow: SharedFlow<MessageChanges> = merge(
            mmsDatabase.changeNotification,
            smsDatabase.changeNotification,
        ).filter { it.changeType == MessageChanges.ChangeType.Added }
            .shareIn(this, SharingStarted.Lazily)

        merge(threadLastSeenFlow, messageAddedFlow)
            .scan(State(threadDb.getAllLastSeen())) { acc, event ->
                when (event) {
                    is MessageChanges -> handleMessageAdded(acc, event)
                    is ThreadUpdated -> handleThreadUpdated(acc, event)
                    else -> error("Unexpected event type $event")
                }
            }
            .mapNotNull { it.action }
            .collect { action ->
                when (action) {
                    is Action.PostOrUpdate -> postNotifications(
                        action.threadId, action.threadAddress, action.newMessages
                    )
                    is Action.CancelThread -> cancelThreadNotification(action.threadId)
                }
            }
    }

    // ── Scan event handlers ──

    private fun handleMessageAdded(acc: State, event: MessageChanges): State {
        val threadId = event.threadId
        val threadAddress = threadDb.getRecipientForThreadId(threadId) ?: return acc.noAction()
        val threadRecipient = recipientRepository.getRecipientSync(threadAddress)
        val lastSeen = acc.lastSeenByThreadIDs.getOrDefault(threadId, 0L)

        val newMessages = mmsSmsDatabase.getMessages(event.ids)
            .filter { msg ->
                msg.isIncoming &&
                    !msg.isControlMessage &&
                    msg.dateSent > lastSeen
            }

        if (newMessages.isEmpty()) return acc.noAction()
        if (!shouldNotify(threadRecipient, newMessages)) return acc.noAction()
        if (isConversationVisible(threadAddress)) return acc.noAction()

        return State(
            lastSeenByThreadIDs = acc.lastSeenByThreadIDs,
            action = Action.PostOrUpdate(threadId, threadAddress, newMessages)
        )
    }

    private fun handleThreadUpdated(acc: State, event: ThreadUpdated): State {
        val oldLastSeen = acc.lastSeenByThreadIDs.getOrDefault(event.threadId, 0L)

        return if (event.lastSeenMs > oldLastSeen) {
            Log.d(TAG, "Thread ${event.threadId} lastSeen advanced $oldLastSeen -> ${event.lastSeenMs}")
            State(
                lastSeenByThreadIDs = acc.lastSeenByThreadIDs.updated(event.threadId, event.lastSeenMs),
                action = Action.CancelThread(event.threadId)
            )
        } else if (acc.action != null) {
            acc.copy(action = null)
        } else {
            acc
        }
    }

    // ── Notification posting ──

    @SuppressLint("MissingPermission")
    private fun postNotifications(threadId: Long, threadAddress: Address, newMessages: List<MessageRecord>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val threadRecipient = recipientRepository.getRecipientSync(threadAddress)

        val isMessageRequest = !threadRecipient.isGroupOrCommunityRecipient &&
            !threadRecipient.approved &&
            !threadDb.threadContainsOutgoingMessage(threadId)

        if (isMessageRequest && threadDb.getMessageCount(threadId) > 1) return

        val items = newMessages.mapNotNull { record ->
            buildNotificationItem(record, threadRecipient, threadId, isMessageRequest)
        }.sortedByDescending { it.timestamp }

        if (items.isEmpty()) return

        if (isMessageRequest) {
            prefs.remove(AppPreferences.HAS_HIDDEN_MESSAGE_REQUESTS)
        }

        val now = System.currentTimeMillis()
        val signal = (now - lastAudibleNotification) >= MIN_AUDIBLE_PERIOD_MILLIS
        if (signal) {
            lastAudibleNotification = now
        }

        val tag = tagForThread(threadId)
        val existingTags = getOurNotificationTags()
        val isMultiThread = existingTags.any { it != tag && it != TAG_SUMMARY }

        sendSingleThreadNotification(
            items, signal,
            bundled = isMultiThread,
            isRequest = isMessageRequest,
            tag = tag,
        )

        if (isMultiThread || existingTags.contains(TAG_SUMMARY)) {
            rebuildGroupSummary(signal)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendSingleThreadNotification(
        items: List<NotificationItem>,
        signal: Boolean,
        bundled: Boolean,
        isRequest: Boolean,
        tag: String,
    ) {
        if (items.isEmpty()) return

        val builder = SingleRecipientNotificationBuilder(
            context,
            NotificationPrivacyPreference(prefs[NotificationPreferences.PRIVACY]),
            avatarUtils,
            imageLoader,
        )

        val first = items.first()
        val messageOriginator = first.recipient
        val timestamp = first.timestamp
        if (timestamp != 0L) builder.setWhen(timestamp)

        builder.setThread(first.recipient)
        builder.setMessageCount(items.size)

        if (first.isMessageRequest) {
            builder.setContentTitle(context.getString(R.string.app_name))
            builder.setLargeIcon(null as android.graphics.Bitmap?)
        }

        val notificationText = first.text ?: ""
        val ss = highlightMentions(
            recipientRepository = recipientRepository,
            text = notificationText,
            isOutgoingMessage = false,
            isQuote = false,
            formatOnly = true,
            context = context
        )

        builder.setPrimaryMessageBody(
            messageOriginator,
            first.individualRecipient,
            ss,
            first.slideDeck
        )

        builder.setContentIntent(first.getPendingIntent(context))
        builder.setDeleteIntent(buildDeleteIntent())
        builder.setOnlyAlertOnce(!signal)
        builder.setAutoCancel(true)

        val replyMethod = ReplyMethod.forRecipient(context, messageOriginator)
        val canReply = canUserReplyToNotification(messageOriginator)
        val threadId = first.threadId
        val notificationId = notificationIdForThread(threadId)

        val quickReplyIntent = if (canReply) buildQuickReplyIntent(messageOriginator) else null
        val remoteReplyIntent = if (canReply) buildRemoteReplyIntent(messageOriginator, replyMethod) else null

        builder.addActions(
            buildMarkAsReadIntent(longArrayOf(threadId), items.maxOf { it.timestamp }),
            quickReplyIntent,
            remoteReplyIntent,
            replyMethod
        )

        if (canReply) {
            builder.addAndroidAutoAction(
                buildAndroidAutoReplyIntent(messageOriginator, threadId),
                buildAndroidAutoHeardIntent(longArrayOf(threadId)),
                first.timestamp
            )
        }

        for (item in items.asReversed()) {
            builder.addMessageBody(item.recipient, item.individualRecipient, item.text)
        }

        if (signal) {
            builder.setAlarms(NotificationChannels.getMessageRingtone(context))
            builder.setTicker(first.individualRecipient, first.text)
        }

        if (bundled || isRequest) {
            builder.setGroup(NOTIFICATION_GROUP)
            builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        }

        NotificationManagerCompat.from(context).notify(tag, notificationId, builder.build())
        Log.i(TAG, "Posted notification with tag $tag")
    }

    @SuppressLint("MissingPermission")
    private fun rebuildGroupSummary(signal: Boolean) {
        val threadTags = getOurNotificationTags().filter { it != TAG_SUMMARY }
        if (threadTags.size < 2) {
            NotificationManagerCompat.from(context).cancel(TAG_SUMMARY, SUMMARY_NOTIFICATION_ID)
            return
        }

        val builder = GroupSummaryNotificationBuilder(context, NotificationPrivacyPreference(prefs[NotificationPreferences.PRIVACY]))
        builder.setGroup(NOTIFICATION_GROUP)

        var totalCount = 0
        var mostRecentRecipient: Recipient? = null
        var mostRecentTimestamp = 0L
        val threadIds = mutableListOf<Long>()

        for (tag in threadTags) {
            val threadId = threadIdFromTag(tag) ?: continue
            threadIds += threadId
            val address = threadDb.getRecipientForThreadId(threadId) ?: continue
            val recipient = recipientRepository.getRecipientSync(address)

            val activeNotif = notificationManager
                .activeNotifications
                ?.firstOrNull { it.tag == tag }
                ?.notification

            val count = activeNotif?.number ?: 1
            totalCount += count

            val timestamp = activeNotif?.`when` ?: 0L
            if (timestamp > mostRecentTimestamp) {
                mostRecentTimestamp = timestamp
                mostRecentRecipient = recipient
            }
        }

        builder.setMessageCount(totalCount, threadTags.size)
        mostRecentRecipient?.let { builder.setMostRecentSender(it) }
        if (mostRecentTimestamp != 0L) builder.setWhen(mostRecentTimestamp)
        builder.setDeleteIntent(buildDeleteIntent())
        builder.setOnlyAlertOnce(!signal)
        builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        builder.setAutoCancel(true)
        builder.addActions(buildMarkAsReadIntent(threadIds.toLongArray(), mostRecentTimestamp))

        NotificationManagerCompat.from(context).notify(TAG_SUMMARY, SUMMARY_NOTIFICATION_ID, builder.build())
    }

    // ── Cancellation ──

    private fun cancelThreadNotification(threadId: Long) {
        val tag = tagForThread(threadId)
        NotificationManagerCompat.from(context).cancel(tag, notificationIdForThread(threadId))
        Log.d(TAG, "Cancelled notification for thread $threadId (tag=$tag)")

        // Update/remove the summary if needed
        val remaining = getOurNotificationTags().filter { it != TAG_SUMMARY && it != tag }
        if (remaining.size < 2) {
            NotificationManagerCompat.from(context).cancel(TAG_SUMMARY, SUMMARY_NOTIFICATION_ID)
        }
    }

    // ── Helpers ──

    /**
     * Returns tags of all currently-active notifications posted by this processor.
     * We identify our own notifications by the [TAG_PREFIX] on their tag.
     */
    private fun getOurNotificationTags(): List<String> {
        return try {
            notificationManager.activeNotifications
                .mapNotNull { it.tag }
                .filter { it.startsWith(TAG_PREFIX) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ── PendingIntent builders ──

    private fun buildDeleteIntent(): PendingIntent {
        val intent = Intent(context, DeleteNotificationReceiver::class.java)
            .setAction(DeleteNotificationReceiver.DELETE_NOTIFICATION_ACTION)
            .setData(Uri.parse("custom://${System.currentTimeMillis()}"))
        return PendingIntent.getBroadcast(context, 0, intent, pendingIntentFlags())
    }

    private fun buildMarkAsReadIntent(threadIds: LongArray, maxTimestamp: Long): PendingIntent {
        val intent = Intent(MarkReadReceiver.CLEAR_ACTION)
            .setClass(context, MarkReadReceiver::class.java)
            .setData(Uri.parse("custom://${System.currentTimeMillis()}"))
            .putExtra(MarkReadReceiver.THREAD_IDS_EXTRA, threadIds)
            .putExtra(MarkReadReceiver.LATEST_TIMESTAMP_EXTRA, maxTimestamp)
        return PendingIntent.getBroadcast(context, 0, intent, pendingIntentFlags())
    }

    private fun buildQuickReplyIntent(recipient: Recipient): PendingIntent {
        val intent = ConversationActivityV2.createIntent(context, recipient.address as Address.Conversable)
            .setData(Uri.parse("custom://${System.currentTimeMillis()}"))
        return PendingIntent.getActivity(context, 0, intent, pendingIntentFlags())
    }

    private fun buildRemoteReplyIntent(recipient: Recipient, replyMethod: ReplyMethod): PendingIntent {
        val intent = Intent(RemoteReplyReceiver.REPLY_ACTION)
            .setClass(context, RemoteReplyReceiver::class.java)
            .setData(Uri.parse("custom://${System.currentTimeMillis()}"))
            .putExtra(RemoteReplyReceiver.ADDRESS_EXTRA, recipient.address)
            .putExtra(RemoteReplyReceiver.REPLY_METHOD, replyMethod)
            .setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 0, intent, pendingIntentFlags())
    }

    private fun buildAndroidAutoReplyIntent(recipient: Recipient, threadId: Long): PendingIntent {
        val intent = Intent(AndroidAutoReplyReceiver.REPLY_ACTION)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .setClass(context, AndroidAutoReplyReceiver::class.java)
            .setData(Uri.parse("custom://${System.currentTimeMillis()}"))
            .putExtra(AndroidAutoReplyReceiver.ADDRESS_EXTRA, recipient.address)
            .putExtra(AndroidAutoReplyReceiver.THREAD_ID_EXTRA, threadId)
            .setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 0, intent, pendingIntentFlags())
    }

    private fun buildAndroidAutoHeardIntent(threadIds: LongArray): PendingIntent {
        val intent = Intent(AndroidAutoHeardReceiver.HEARD_ACTION)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .setClass(context, AndroidAutoHeardReceiver::class.java)
            .setData(Uri.parse("custom://${System.currentTimeMillis()}"))
            .putExtra(AndroidAutoHeardReceiver.THREAD_IDS_EXTRA, threadIds)
            .setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 0, intent, pendingIntentFlags())
    }

    private fun shouldNotify(
        threadRecipient: Recipient,
        messages: List<MessageRecord>
    ): Boolean {
        if (!prefs[NotificationPreferences.ENABLE]) return false
        if (threadRecipient.isMuted()) return false
        if (threadRecipient.notifyType == NotifyType.NONE) return false

        if (threadRecipient.notifyType == NotifyType.MENTIONS) {
            val userPublicKey = loginStateRepository.requireLocalNumber()
            val blindedPublicKey = generateBlindedId(threadRecipient)

            val hasMention = messages.any { msg ->
                val body = msg.body
                body.contains("@$userPublicKey") ||
                    (blindedPublicKey != null && body.contains("@$blindedPublicKey")) ||
                    (msg is MmsMessageRecord && msg.quote?.let { quote ->
                        val quoteAuthor = quote.author.toString()
                        quoteAuthor == userPublicKey ||
                            (blindedPublicKey != null && quoteAuthor == blindedPublicKey)
                    } == true)
            }
            if (!hasMention) return false
        }

        return true
    }

    private fun isConversationVisible(threadAddress: Address): Boolean {
        val activity = currentActivityObserver.currentActivity.value
        if (activity is ConversationActivityV2) {
            val visibleAddress = IntentCompat.getParcelableExtra(
                activity.intent, "address", Address.Conversable::class.java
            )
            return visibleAddress == threadAddress
        }
        return false
    }

    private fun buildNotificationItem(
        record: MessageRecord,
        threadRecipient: Recipient,
        threadId: Long,
        isMessageRequest: Boolean,
    ): NotificationItem? {
        if (record.isIncomingCall || record.isOutgoingCall) return null

        var body: CharSequence = messageFormatter.get().formatMessageBody(
            context = context,
            message = record,
            threadRecipient = threadRecipient,
        )

        var slideDeck: SlideDeck? = null

        if (isMessageRequest) {
            body = SpanUtil.italic(context.getString(R.string.messageRequestsNew))
        } else if (KeyCachingService.isLocked(context)) {
            body = SpanUtil.italic(
                context.resources.getQuantityString(R.plurals.messageNewYouveGot, 1, 1)
            )
        } else {
            if (record.isMms && TextUtils.isEmpty(body) && (record as MmsMessageRecord).slideDeck.slides.isNotEmpty()) {
                slideDeck = (record as org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord).slideDeck
                body = SpanUtil.italic(slideDeck.body)
            } else if (record.isMms && !record.isMmsNotification && (record as MmsMessageRecord).slideDeck.slides.isNotEmpty()) {
                slideDeck = (record as org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord).slideDeck
                val message = slideDeck.body + ": " + record.body
                val italicLength = message.length - body.length
                body = SpanUtil.italic(message, italicLength)
            } else if (record.isOpenGroupInvitation) {
                body = SpanUtil.italic(context.getString(R.string.communityInvitation))
            }
        }

        return NotificationItem(
            record.getId(),
            record.isMms || record.isMmsNotification,
            record.individualRecipient,
            record.recipient,
            threadRecipient,
            threadId,
            body,
            record.timestamp,
            slideDeck,
            isMessageRequest,
        )
    }

    private fun generateBlindedId(threadRecipient: Recipient): String? {
        val recipientData = threadRecipient.data
        val serverPubKey = (recipientData as? RecipientData.Community)?.serverPubKey
        val loginState = loginStateRepository.peekLoginState()
        if (serverPubKey != null && loginState != null) {
            val blindedKeyPair = loginState.getBlindedKeyPair(
                serverUrl = recipientData.serverUrl,
                serverPubKeyHex = serverPubKey
            )
            return AccountId(IdPrefix.BLINDED, blindedKeyPair.pubKey.data).hexString
        }
        return null
    }

    // ── Data classes ──

    private data class ThreadUpdated(
        val threadId: Long,
        val threadAddress: Address.Conversable,
        val lastSeenMs: Long
    )

    private data class State(
        val lastSeenByThreadIDs: LongLongMap,
        val action: Action? = null,
    ) {
        fun noAction(): State = if (action != null) copy(action = null) else this
    }

    private sealed interface Action {
        data class PostOrUpdate(
            val threadId: Long,
            val threadAddress: Address,
            val newMessages: List<MessageRecord>,
        ) : Action
        data class CancelThread(val threadId: Long) : Action
    }

    companion object {
        private const val TAG = "NotificationProcessor"

        const val EXTRA_REMOTE_REPLY = "extra_remote_reply"

        /**
         * All notifications posted by this processor are tagged with this prefix followed by
         * the thread ID (e.g. "session_msg_12345"). The summary uses [TAG_SUMMARY].
         * This lets us identify and cancel only our own notifications without interfering
         * with call notifications, foreground service notifications, etc.
         */
        private const val TAG_PREFIX = "session_msg_"
        private const val TAG_SUMMARY = "${TAG_PREFIX}summary"

        /** Base offset for per-thread notification IDs. Each thread's ID is this + threadId. */
        private const val THREAD_NOTIFICATION_ID_BASE = 100_000
        /** Fixed ID for the group summary notification. */
        private const val SUMMARY_NOTIFICATION_ID = 100_000 - 1

        private fun notificationIdForThread(threadId: Long): Int = THREAD_NOTIFICATION_ID_BASE + threadId.toInt()

        private const val NOTIFICATION_GROUP = "messages"

        private val MIN_AUDIBLE_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(5)

        @Volatile
        private var lastAudibleNotification: Long = -1

        private fun pendingIntentFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            return flags
        }

        private fun tagForThread(threadId: Long): String = "$TAG_PREFIX$threadId"

        private fun threadIdFromTag(tag: String): Long? {
            return tag.removePrefix(TAG_PREFIX).toLongOrNull()
        }

        private fun LongLongMap.updated(key: Long, value: Long): LongLongMap {
            val map = MutableLongLongMap(size + if (containsKey(key)) 0 else 1)
            map.putAll(this)
            map[key] = value
            return map
        }
    }
}

/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.AsyncTask
import android.text.TextUtils
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.annimon.stream.Stream
import com.squareup.phrase.Phrase
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.ServiceUtil
import org.session.libsession.utilities.StringSubstitutionConstants.EMOJI_KEY
import org.session.libsession.utilities.TextSecurePreferences.Companion.getLocalNumber
import org.session.libsession.utilities.TextSecurePreferences.Companion.getNotificationPrivacy
import org.session.libsession.utilities.TextSecurePreferences.Companion.getRepeatAlertsCount
import org.session.libsession.utilities.TextSecurePreferences.Companion.hasHiddenMessageRequests
import org.session.libsession.utilities.TextSecurePreferences.Companion.isNotificationsEnabled
import org.session.libsession.utilities.TextSecurePreferences.Companion.removeHasHiddenMessageRequests
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Util
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities.highlightMentions
import org.thoughtcrime.securesms.crypto.KeyPairUtilities.getUserED25519KeyPair
import org.thoughtcrime.securesms.database.MmsDatabase.Companion.MESSAGE_BOX
import org.thoughtcrime.securesms.database.MmsSmsColumns
import org.thoughtcrime.securesms.database.MmsSmsColumns.NOTIFIED
import org.thoughtcrime.securesms.database.MmsSmsColumns.READ
import org.thoughtcrime.securesms.database.MmsSmsDatabase.MMS_TRANSPORT
import org.thoughtcrime.securesms.database.MmsSmsDatabase.TRANSPORT
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.dependencies.DatabaseComponent.Companion.get
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.WEBRTC_NOTIFICATION
import org.thoughtcrime.securesms.util.SessionMetaProtocol.canUserReplyToNotification
import org.thoughtcrime.securesms.util.SpanUtil

/**
 * Handles posting system notifications for new messages.
 *
 *
 * @author Moxie Marlinspike
 */
private const val CONTENT_SIGNATURE = "content_signature"
class DefaultMessageNotifier(
    val avatarUtils: AvatarUtils
) : MessageNotifier {
    override fun setVisibleThread(threadId: Long) {
        visibleThread = threadId
    }

    override fun setHomeScreenVisible(isVisible: Boolean) {
        homeScreenVisible = isVisible
    }

    override fun setLastDesktopActivityTimestamp(timestamp: Long) {
        lastDesktopActivityTimestamp = timestamp
    }

    override fun notifyMessageDeliveryFailed(context: Context?, recipient: Recipient?, threadId: Long) {
        // We do not provide notifications for message delivery failure.
    }

    override fun cancelDelayedNotifications() {
        executor.cancel()
    }

    private fun cancelActiveNotifications(context: Context): Boolean {
        val notifications = ServiceUtil.getNotificationManager(context)
        val hasNotifications = notifications.activeNotifications.size > 0
        notifications.cancel(SUMMARY_NOTIFICATION_ID)

        try {
            val activeNotifications = notifications.activeNotifications

            for (activeNotification in activeNotifications) {
                if(activeNotification.id != WEBRTC_NOTIFICATION) {
                    notifications.cancel(activeNotification.id)
                }
            }
        } catch (e: Throwable) {
            // XXX Appears to be a ROM bug, see #6043
            Log.w(TAG, "cancel notification error: $e")
            notifications.cancelAll()
        }
        return hasNotifications
    }

    private fun cancelOrphanedNotifications(context: Context, notificationState: NotificationState) {
        try {
            val notifications = ServiceUtil.getNotificationManager(context)
            val activeNotifications = notifications.activeNotifications

            for (notification in activeNotifications) {
                var validNotification = false

                if (notification.id != SUMMARY_NOTIFICATION_ID && notification.id != KeyCachingService.SERVICE_RUNNING_ID && notification.id != FOREGROUND_ID && notification.id != PENDING_MESSAGES_ID) {
                    for (item in notificationState.notifications) {
                        if (notification.id.toLong() == (SUMMARY_NOTIFICATION_ID + item.threadId)) {
                            validNotification = true
                            break
                        }
                    }

                    if (!validNotification) {
                        if(notification.id != WEBRTC_NOTIFICATION) {
                            notifications.cancel(notification.id)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // XXX Android ROM Bug, see #6043
            Log.w(TAG, e)
        }
    }

    override fun updateNotification(context: Context) {
        if (!isNotificationsEnabled(context)) {
            return
        }

        updateNotification(context, false, 0)
    }

    override fun updateNotification(context: Context, threadId: Long) {
        if (System.currentTimeMillis() - lastDesktopActivityTimestamp < DESKTOP_ACTIVITY_PERIOD) {
            Log.i(TAG, "Scheduling delayed notification...")
            executor.execute(DelayedNotification(context, threadId))
        } else {
            updateNotification(context, threadId, true)
        }
    }

    override fun updateNotification(context: Context, threadId: Long, signal: Boolean) {
        val isVisible = visibleThread == threadId

        val threads = get(context).threadDatabase()
        val recipient = threads.getRecipientForThreadId(threadId)

        if (recipient != null && !recipient.isGroupOrCommunityRecipient && threads.getMessageCount(threadId) == 1 &&
            !(recipient.isApproved || threads.getLastSeenAndHasSent(threadId).second())
        ) {
            removeHasHiddenMessageRequests(context)
        }

        if (!isNotificationsEnabled(context) ||
            (recipient != null && recipient.isMuted)
        ) {
            return
        }

        if ((!isVisible && !homeScreenVisible) || hasExistingNotifications(context)) {
            updateNotification(context, signal, 0)
        }
    }

    private fun hasExistingNotifications(context: Context): Boolean {
        val notifications = ServiceUtil.getNotificationManager(context)
        try {
            val activeNotifications = notifications.activeNotifications
            return activeNotifications.isNotEmpty()
        } catch (e: Exception) {
            return false
        }
    }

    override fun updateNotification(context: Context, signal: Boolean, reminderCount: Int) {
        var playNotificationAudio = signal // Local copy of the argument so we can modify it
        var telcoCursor: Cursor? = null

        try {
            telcoCursor = get(context).mmsSmsDatabase().unreadOrUnseenReactions // TODO: add a notification specific lighter query here

            if ((telcoCursor == null || telcoCursor.isAfterLast) || getLocalNumber(context) == null) {
                cancelActiveNotifications(context)
                clearReminder(context)
                return
            }

            try {
                val notificationState = constructNotificationState(context, telcoCursor)

                if (playNotificationAudio && (System.currentTimeMillis() - lastAudibleNotification) < MIN_AUDIBLE_PERIOD_MILLIS) {
                    playNotificationAudio = false
                } else if (playNotificationAudio) {
                    lastAudibleNotification = System.currentTimeMillis()
                }

                if (notificationState.hasMultipleThreads()) {
                    for (threadId in notificationState.threads) {
                        sendSingleThreadNotification(context, NotificationState(notificationState.getNotificationsForThread(threadId)), false, true)
                    }
                    sendMultipleThreadNotification(context, notificationState, playNotificationAudio)
                } else if (notificationState.notificationCount > 0) {
                    sendSingleThreadNotification(context, notificationState, playNotificationAudio, false)
                } else {
                    cancelActiveNotifications(context)
                }

                cancelOrphanedNotifications(context, notificationState)

                if (playNotificationAudio) {
                    scheduleReminder(context, reminderCount)
                }
            }
            catch (e: Exception) {
                Log.e(TAG, "Error creating notification", e)
            }

        } finally {
            telcoCursor?.close()
        }
    }

    // Note: The `signal` parameter means "play an audio signal for the notification".
    private fun sendSingleThreadNotification(
        context: Context,
        notificationState: NotificationState,
        signal: Boolean,
        bundled: Boolean
    ) {
        Log.i(TAG, "sendSingleThreadNotification()  signal: $signal  bundled: $bundled")

        if (notificationState.notifications.isEmpty()) {
            if (!bundled) {
                cancelActiveNotifications(context)
            }
            Log.i(TAG, "Empty notification state. Skipping.")
            return
        }

        // Bail early if the existing displayed notification has the same content as what we are trying to send now
        val notifications = notificationState.notifications
        val notificationId = (SUMMARY_NOTIFICATION_ID + (if (bundled) notifications[0].threadId else 0)).toInt()
        val contentSignature = notifications.map {
            getNotificationSignature(it)
        }.sorted().joinToString("|")

        val existingNotifications = ServiceUtil.getNotificationManager(context).activeNotifications
        val existingSignature = existingNotifications.find { it.id == notificationId }?.notification?.extras?.getString(CONTENT_SIGNATURE)

        if (existingSignature == contentSignature) {
            Log.i(TAG, "Skipping duplicate single thread notification for ID $notificationId")
            return
        }

        val builder = SingleRecipientNotificationBuilder(context, getNotificationPrivacy(context), avatarUtils)
        builder.putStringExtra(CONTENT_SIGNATURE, contentSignature)

        val messageOriginator = notifications[0].recipient
        val messageIdTag = notifications[0].timestamp.toString()

        val timestamp = notifications[0].timestamp
        if (timestamp != 0L) builder.setWhen(timestamp)

        builder.putStringExtra(LATEST_MESSAGE_ID_TAG, messageIdTag)

        val notificationText = notifications[0].text

        builder.setThread(notifications[0].recipient)
        builder.setMessageCount(notificationState.notificationCount)

        val builderCS = notificationText ?: ""
        val ss = highlightMentions(
            builderCS,
            false,
            false,
            true,
            if (bundled) notifications[0].threadId else 0,
            context
        )

        builder.setPrimaryMessageBody(
            messageOriginator,
            notifications[0].individualRecipient,
            ss,
            notifications[0].slideDeck
        )

        builder.setContentIntent(notifications[0].getPendingIntent(context))
        builder.setDeleteIntent(notificationState.getDeleteIntent(context))
        builder.setOnlyAlertOnce(!signal)
        builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        builder.setAutoCancel(true)

        val replyMethod = ReplyMethod.forRecipient(context, messageOriginator)

        val canReply = canUserReplyToNotification(messageOriginator)

        val quickReplyIntent = if (canReply) notificationState.getQuickReplyIntent(context, messageOriginator) else null
        val remoteReplyIntent = if (canReply) notificationState.getRemoteReplyIntent(context, messageOriginator, replyMethod) else null

        builder.addActions(
            notificationState.getMarkAsReadIntent(context, notificationId),
            quickReplyIntent,
            remoteReplyIntent,
            replyMethod
        )

        if (canReply) {
            builder.addAndroidAutoAction(
                notificationState.getAndroidAutoReplyIntent(context, messageOriginator),
                notificationState.getAndroidAutoHeardIntent(context, notificationId),
                notifications[0].timestamp
            )
        }

        val iterator: ListIterator<NotificationItem> = notifications.listIterator(notifications.size)
        while (iterator.hasPrevious()) {
            val item = iterator.previous()
            builder.addMessageBody(item.recipient, item.individualRecipient, item.text)
        }

        if (signal) {
            builder.setAlarms(notificationState.getRingtone(context), notificationState.vibrate)
            builder.setTicker(
                notifications[0].individualRecipient,
                notifications[0].text
            )
        }

        if (bundled) {
            builder.setGroup(NOTIFICATION_GROUP)
            builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        }

        val notification = builder.build()

        // TODO - ACL to fix this properly & will do on 2024-08-26, but just skipping for now so review can start
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }

        NotificationManagerCompat.from(context).notify(notificationId, notification)
        Log.i(TAG, "Posted notification. $notification")
    }

    private fun getNotificationSignature(notification: NotificationItem): String {
        return "${notification.id}_${notification.text}_${notification.timestamp}_${notification.threadId}"
    }

    // Note: The `signal` parameter means "play an audio signal for the notification".
    private fun sendMultipleThreadNotification(
        context: Context,
        notificationState: NotificationState,
        signal: Boolean
    ) {
        Log.i(TAG, "sendMultiThreadNotification()  signal: $signal")

        val notifications = notificationState.notifications
        val contentSignature = notifications.map {
            getNotificationSignature(it)
        }.sorted().joinToString("|")

        val existingNotifications = ServiceUtil.getNotificationManager(context).activeNotifications
        val existingSignature = existingNotifications.find { it.id == SUMMARY_NOTIFICATION_ID }?.notification?.extras?.getString(CONTENT_SIGNATURE)

        if (existingSignature == contentSignature) {
            Log.i(TAG, "Skipping duplicate multi-thread notification")
            return
        }

        val builder = MultipleRecipientNotificationBuilder(context, getNotificationPrivacy(context))
        builder.putStringExtra(CONTENT_SIGNATURE, contentSignature)

        builder.setMessageCount(notificationState.notificationCount, notificationState.threadCount)
        builder.setMostRecentSender(notifications[0].individualRecipient, notifications[0].recipient)
        builder.setGroup(NOTIFICATION_GROUP)
        builder.setDeleteIntent(notificationState.getDeleteIntent(context))
        builder.setOnlyAlertOnce(!signal)
        builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        builder.setAutoCancel(true)

        val messageIdTag = notifications[0].timestamp.toString()

        val notificationManager = ServiceUtil.getNotificationManager(context)
        for (notification in notificationManager.activeNotifications) {
            if (notification.id == SUMMARY_NOTIFICATION_ID && messageIdTag == notification.notification.extras.getString(LATEST_MESSAGE_ID_TAG)) {
                return
            }
        }

        val timestamp = notifications[0].timestamp
        if (timestamp != 0L) builder.setWhen(timestamp)

        builder.addActions(notificationState.getMarkAsReadIntent(context, SUMMARY_NOTIFICATION_ID))

        val iterator: ListIterator<NotificationItem> = notifications.listIterator(notifications.size)
        while (iterator.hasPrevious()) {
            val item = iterator.previous()
            builder.addMessageBody(
                item.individualRecipient, item.recipient,
                highlightMentions(
                    (if (item.text != null) item.text else "")!!,
                    false,
                    false,
                    true,  // no styling here, only text formatting
                    item.threadId,
                    context
                )
            )
        }

        if (signal) {
            builder.setAlarms(notificationState.getRingtone(context), notificationState.vibrate)
            val text = notifications[0].text
            builder.setTicker(
                notifications[0].individualRecipient,
                highlightMentions(
                    text ?: "",
                    false,
                    false,
                    true,  // no styling here, only text formatting
                    notifications[0].threadId,
                    context
                )
            )
        }

        builder.putStringExtra(LATEST_MESSAGE_ID_TAG, messageIdTag)

        // TODO - ACL to fix this properly & will do on 2024-08-26, but just skipping for now so review can start
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }

        val notification = builder.build()
        NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, notification)
        Log.i(TAG, "Posted notification. $notification")
    }

    private fun constructNotificationState(context: Context, cursor: Cursor): NotificationState {
        val notificationState = NotificationState()
        val reader = get(context).mmsSmsDatabase().readerFor(cursor)
        if (reader == null) {
            Log.e(TAG, "No reader for cursor - aborting constructNotificationState")
            return NotificationState()
        }

        val threadDatabase = get(context).threadDatabase()
        val cache: MutableMap<Long, String?> = HashMap()

        var record: MessageRecord? = null
        do {
            record = reader.next
            if (record == null) break // Bail if there are no more MessageRecords

            val threadId = record.threadId
            val threadRecipients = if (threadId != -1L) {
                threadDatabase.getRecipientForThreadId(threadId)
            } else null

            // Start by checking various scenario that we should skip

            // Skip if muted or calls
            if (threadRecipients?.isMuted == true) continue
            if (record.isIncomingCall || record.isOutgoingCall) continue

            // Handle message requests early
            val isMessageRequest = threadRecipients != null &&
                    !threadRecipients.isGroupOrCommunityRecipient &&
                    !threadRecipients.isApproved &&
                    !threadDatabase.getLastSeenAndHasSent(threadId).second()

            if (isMessageRequest && (threadDatabase.getMessageCount(threadId) > 1 || !hasHiddenMessageRequests(context))) {
                continue
            }

            // Check notification settings
            if (threadRecipients?.notifyType == RecipientDatabase.NOTIFY_TYPE_NONE) continue

            val userPublicKey = getLocalNumber(context)

            // Check mentions-only setting
            if (threadRecipients?.notifyType == RecipientDatabase.NOTIFY_TYPE_MENTIONS) {
                var blindedPublicKey = cache[threadId]
                if (blindedPublicKey == null) {
                    blindedPublicKey = generateBlindedId(threadId, context)
                    cache[threadId] = blindedPublicKey
                }

                var isMentioned = false
                val body = record.getDisplayBody(context).toString()

                // Check for @mentions
                if (body.contains("@$userPublicKey") ||
                    (blindedPublicKey != null && body.contains("@$blindedPublicKey"))) {
                    isMentioned = true
                }

                // Check for quote mentions
                if (record is MmsMessageRecord) {
                    val quote = record.quote
                    val quoteAuthor = quote?.author?.toString()
                    if ((quoteAuthor != null && userPublicKey == quoteAuthor) ||
                        (blindedPublicKey != null && quoteAuthor == blindedPublicKey)) {
                        isMentioned = true
                    }
                }

                if (!isMentioned) continue
            }

            Log.w(TAG, "Processing: ID=${record.getId()}, outgoing=${record.isOutgoing}, read=${record.isRead}, hasReactions=${record.reactions.isNotEmpty()}")

            // Determine the reason this message was returned by the query
            val isNotified = cursor.getInt(cursor.getColumnIndexOrThrow(NOTIFIED)) == 1
            val isUnreadIncoming = !record.isOutgoing && !record.isRead() && !isNotified // << Case 1
            val hasUnreadReactions = record.reactions.isNotEmpty() // << Case 2

            Log.w(TAG, "  -> isUnreadIncoming=$isUnreadIncoming, hasUnreadReactions=$hasUnreadReactions, isNotified=${isNotified}")

            // CASE 1: TRULY NEW UNREAD INCOMING MESSAGE
            // Only show message notification if it's incoming, unread AND not yet notified
            if (isUnreadIncoming) {
                // Prepare message body
                var body: CharSequence = record.getDisplayBody(context)
                var slideDeck: SlideDeck? = null

                if (isMessageRequest) {
                    body = SpanUtil.italic(context.getString(R.string.messageRequestsNew))
                } else if (KeyCachingService.isLocked(context)) {
                    body = SpanUtil.italic(context.resources.getQuantityString(R.plurals.messageNewYouveGot, 1, 1))
                } else {
                    // Handle MMS content
                    if (record.isMms && TextUtils.isEmpty(body) && (record as MmsMessageRecord).slideDeck.slides.isNotEmpty()) {
                        slideDeck = (record as MediaMmsMessageRecord).slideDeck
                        body = SpanUtil.italic(slideDeck.body)
                    } else if (record.isMms && !record.isMmsNotification && (record as MmsMessageRecord).slideDeck.slides.isNotEmpty()) {
                        slideDeck = (record as MediaMmsMessageRecord).slideDeck
                        val message = slideDeck.body + ": " + record.body
                        val italicLength = message.length - body.length
                        body = SpanUtil.italic(message, italicLength)
                    } else if (record.isOpenGroupInvitation) {
                        body = SpanUtil.italic(context.getString(R.string.communityInvitation))
                    }
                }

                Log.w(TAG, "Adding incoming message notification: ${body}")

                // Add incoming message notification
                notificationState.addNotification(
                    NotificationItem(
                        record.getId(),
                        record.isMms || record.isMmsNotification,
                        record.individualRecipient,
                        record.recipient,
                        threadRecipients,
                        threadId,
                        body,
                        record.timestamp,
                        slideDeck
                    )
                )
            }
            // CASE 2: REACTIONS TO OUR OUTGOING MESSAGES
            // Only if: it's OUR message AND it has reactions AND it's NOT an unread incoming message
            else if (record.isOutgoing &&
                hasUnreadReactions &&
                threadRecipients != null &&
                !threadRecipients.isGroupOrCommunityRecipient) {

                var blindedPublicKey = cache[threadId]
                if (blindedPublicKey == null) {
                    blindedPublicKey = generateBlindedId(threadId, context)
                    cache[threadId] = blindedPublicKey
                }

                // Find reactions from others (not from us)
                val reactionsFromOthers = record.reactions.filter { reaction ->
                    reaction.author != userPublicKey &&
                            (blindedPublicKey == null || reaction.author != blindedPublicKey)
                }

                if (reactionsFromOthers.isNotEmpty()) {
                    // Get the most recent reaction from others
                    val latestReaction = reactionsFromOthers.maxByOrNull { it.dateSent }

                    if (latestReaction != null) {
                        val reactor = Recipient.from(context, fromSerialized(latestReaction.author), false)
                        val emoji = Phrase.from(context, R.string.emojiReactsNotification)
                            .put(EMOJI_KEY, latestReaction.emoji).format().toString()

                        // Use unique ID to avoid conflicts with message notifications
                        val reactionId = "reaction_${record.getId()}_${latestReaction.emoji}_${latestReaction.author}".hashCode().toLong()

                        Log.w(TAG, "Adding reaction notification: ${emoji} to our message ID ${record.getId()}")

                        notificationState.addNotification(
                            NotificationItem(
                                reactionId,
                                record.isMms || record.isMmsNotification,
                                reactor,
                                reactor,
                                threadRecipients,
                                threadId,
                                emoji,
                                latestReaction.dateSent,
                                null
                            )
                        )
                    }
                }
            }
            // CASE 3: IGNORED SCENARIOS
            // This handles cases like:
            // - Contact's message with reactions (hasUnreadReactions=true, but isOutgoing=false)
            // - Already read messages that somehow got returned
            // - etc.
            else {
                Log.w(TAG, "Ignoring message: not unread incoming and not our outgoing with reactions")
            }

        } while (record != null)

        reader.close()
        return notificationState
    }

    private fun generateBlindedId(threadId: Long, context: Context): String? {
        val lokiThreadDatabase = get(context).lokiThreadDatabase()
        val openGroup = lokiThreadDatabase.getOpenGroupChat(threadId)
        val edKeyPair = getUserED25519KeyPair(context)
        if (openGroup != null && edKeyPair != null) {
            val blindedKeyPair = BlindKeyAPI.blind15KeyPairOrNull(
                ed25519SecretKey = edKeyPair.secretKey.data,
                serverPubKey = Hex.fromStringCondensed(openGroup.publicKey),
            )
            if (blindedKeyPair != null) {
                return AccountId(IdPrefix.BLINDED, blindedKeyPair.pubKey.data).hexString
            }
        }
        return null
    }

    private fun scheduleReminder(context: Context, count: Int) {
        if (count >= getRepeatAlertsCount(context)) {
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarmIntent = Intent(ReminderReceiver.REMINDER_ACTION)
        alarmIntent.putExtra("reminder_count", count)

        val pendingIntent = PendingIntent.getBroadcast(context, 0, alarmIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val timeout = TimeUnit.MINUTES.toMillis(2)

        alarmManager[AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + timeout] = pendingIntent
    }

    override fun clearReminder(context: Context) {
        val alarmIntent = Intent(ReminderReceiver.REMINDER_ACTION)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, alarmIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
    }

    class ReminderReceiver : BroadcastReceiver() {
        @SuppressLint("StaticFieldLeak")
        override fun onReceive(context: Context, intent: Intent) {
            object : AsyncTask<Void?, Void?, Void?>() {

                override fun doInBackground(vararg params: Void?): Void? {
                    val reminderCount = intent.getIntExtra("reminder_count", 0)
                    ApplicationContext.getInstance(context).messageNotifier.updateNotification(context, true, reminderCount + 1)
                    return null
                }

            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }

        companion object {
            const val REMINDER_ACTION: String = "network.loki.securesms.MessageNotifier.REMINDER_ACTION"
        }
    }

    private class DelayedNotification(private val context: Context, private val threadId: Long) : Runnable {
        private val canceled = AtomicBoolean(false)

        private val delayUntil: Long

        init {
            this.delayUntil = System.currentTimeMillis() + DELAY
        }

        override fun run() {
            val delayMillis = delayUntil - System.currentTimeMillis()
            Log.i(TAG, "Waiting to notify: $delayMillis")

            if (delayMillis > 0) { Util.sleep(delayMillis) }

            if (!canceled.get()) {
                Log.i(TAG, "Not canceled, notifying...")
                ApplicationContext.getInstance(context).messageNotifier.updateNotification(context, threadId, true)
                ApplicationContext.getInstance(context).messageNotifier.cancelDelayedNotifications()
            } else {
                Log.w(TAG, "Canceled, not notifying...")
            }
        }

        fun cancel() {
            canceled.set(true)
        }

        companion object {
            private val DELAY = TimeUnit.SECONDS.toMillis(5)
        }
    }

    private class CancelableExecutor {
        private val executor: Executor = Executors.newSingleThreadExecutor()
        private val tasks: MutableSet<DelayedNotification> = HashSet()

        fun execute(runnable: DelayedNotification) {
            synchronized(tasks) { tasks.add(runnable) }

            val wrapper = Runnable {
                runnable.run()
                synchronized(tasks) {
                    tasks.remove(runnable)
                }
            }

            executor.execute(wrapper)
        }

        fun cancel() {
            synchronized(tasks) {
                for (task in tasks) { task.cancel() }
            }
        }
    }

    companion object {
        private val TAG: String = DefaultMessageNotifier::class.java.simpleName

        const val EXTRA_REMOTE_REPLY: String = "extra_remote_reply"
        const val LATEST_MESSAGE_ID_TAG: String = "extra_latest_message_id"

        private const val FOREGROUND_ID = 313399
        private const val SUMMARY_NOTIFICATION_ID = 1338
        private const val PENDING_MESSAGES_ID = 1111
        private const val NOTIFICATION_GROUP = "messages"
        private val MIN_AUDIBLE_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(5)
        private val DESKTOP_ACTIVITY_PERIOD = TimeUnit.MINUTES.toMillis(1)

        @Volatile
        private var visibleThread: Long = -1

        @Volatile
        private var homeScreenVisible = false

        @Volatile
        private var lastDesktopActivityTimestamp: Long = -1

        @Volatile
        private var lastAudibleNotification: Long = -1
        private val executor = CancelableExecutor()
    }
}

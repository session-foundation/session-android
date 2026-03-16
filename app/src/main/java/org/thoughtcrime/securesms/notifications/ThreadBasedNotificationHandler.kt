package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.drawable.IconCompat
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v3.ConversationActivityV3
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.ThreadId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import kotlin.collections.forEach

abstract class ThreadBasedNotificationHandler(
    protected val context: Context,
    protected val currentActivityObserver: CurrentActivityObserver,
    protected val avatarUtils: AvatarUtils,
    protected val channels: NotificationChannelManager,
    protected val recipientRepository: RecipientRepository,
    private val avatarBitmapCache: AvatarBitmapCache,
    protected val notificationManager: NotificationManagerCompat,
    protected val prefs: PreferenceStorage,
    private val appVisibilityManager: AppVisibilityManager,
) {
    protected val currentActivity get() = currentActivityObserver.currentActivity.value
    protected val currentlyShowingConversation: Address.Conversable?
        get() = currentActivityObserver.currentlyShowingConversation

    protected fun cancelThreadNotification(threadId: Long) {
        notificationManager.cancel(threadTag(threadId), NotificationId.MESSAGE_THREAD)
    }

    protected fun getActiveThreadNotification(threadId: ThreadId): StatusBarNotification? {
        return notificationManager.activeNotifications.firstOrNull {
            it.tag == threadTag(threadId) && it.id == NotificationId.MESSAGE_THREAD
        }
    }

    protected suspend fun Recipient.buildPerson(): Person {
        return Person.Builder()
            .setName(displayName())
            .setIcon(getIcon(avatarUtils.getUIDataFromRecipient(this)))
            .build()
    }

    protected suspend fun MessageRecord.toPerson(personCache: MutableMap<Address, Person>?): Person {
        if (personCache == null) {
            return individualRecipient.buildPerson()
        }

        return personCache.getOrPut(individualRecipient.address) {
            individualRecipient.buildPerson()
        }
    }

    protected suspend fun ReactionRecord.toPerson(personCache: MutableMap<Address, Person>): Person {
        val address = author.toAddress()
        return personCache.getOrPut(address) {
            recipientRepository.getRecipientSync(address).buildPerson()
        }
    }

    protected suspend fun postOrUpdateNotification(
        threadAddress: Address.Conversable,
        threadRecipient: Recipient,
        threadId: ThreadId,
        latestMessageTimestampMs: Long,
        messages: List<NotificationCompat.MessagingStyle.Message>,
        canReply: Boolean,
        silent: Boolean
    ) {
        require(messages.isNotEmpty()) {
            "Messages cannot be empty: this method does not handle empty message case"
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "threadId=$threadId: POST_NOTIFICATIONS permission not granted — cannot post notification")
            return
        }

        val channelDesc = when (threadAddress) {
            is Address.Community -> NotificationChannelManager.ChannelDescription.COMMUNITY_MESSAGES
            is Address.Group,
            is Address.LegacyGroup -> NotificationChannelManager.ChannelDescription.GROUP_MESSAGES

            is Address.CommunityBlindedId,
            is Address.Standard -> NotificationChannelManager.ChannelDescription.ONE_TO_ONE_MESSAGES
        }

        val builder =
            NotificationCompat.Builder(context, channels.getNotificationChannelId(channelDesc))
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.textsecure_primary))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(silent)
                .setAutoCancel(true)
                .setSilent(!prefs[NotificationPreferences.SOUND_WHEN_APP_OPEN] && appVisibilityManager.isAppVisible.value)

        val userPerson = Person.Builder()
            .setName(context.getString(R.string.you))
            .setIcon(getIcon(avatarUtils.getUIDataFromRecipient(recipientRepository.getSelf())))
            .build()

        val style = NotificationCompat.MessagingStyle(userPerson)

        if (threadAddress is Address.GroupLike) {
            style.setConversationTitle(threadRecipient.displayName())
                .setGroupConversation(true)
        }

        messages.forEach(style::addMessage)

        builder.setStyle(style)
        builder.setLargeIcon(
            avatarBitmapCache.get(
                avatarUtils.getUIDataFromRecipient(
                    threadRecipient
                )
            )
        )

        val intent = ConversationActivityV2.createIntent(context, threadAddress)
        val pendingIntent = PendingIntent.getActivity(
            context,
            threadAddress.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)

        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_check,
                context.getString(R.string.messageMarkRead),
                NotificationActionReceiver.buildMarkReadIntent(
                    context = context,
                    threadAddress = threadAddress,
                    latestMessageTimestampMs = latestMessageTimestampMs
                )
            ).build()
        )

        // Reply action (not applicable for message request threads)
        if (canReply) {
            val (replyIntent, remoteInput) = NotificationActionReceiver.buildReplyIntent(
                context = context,
                threadAddress = threadAddress
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

        Log.d(TAG, "threadId=$threadId: posting notification — ${messages.size} message(s), silent=$silent, canReply=$canReply, channel=$channelDesc")
        notificationManager.notify(
            threadTag(threadId),
            NotificationId.MESSAGE_THREAD,
            builder.build()
        )
    }

    private suspend fun getIcon(avatarUIData: AvatarUIData): IconCompat =
        IconCompat.createWithBitmap(avatarBitmapCache.get(avatarUIData))


    companion object {
        private const val TAG = "ThreadBasedNotificationHandler"

        fun threadTag(threadId: Long): String = "thread-$threadId"

        val ConversationActivityV2.threadAddress: Address.Conversable?
            get() = IntentCompat.getParcelableExtra(
                intent,
                ConversationActivityV2.ADDRESS,
                Address.Conversable::class.java
            )

        val ConversationActivityV3.threadAddress: Address.Conversable?
            get() = IntentCompat.getParcelableExtra(
                intent,
                ConversationActivityV3.ADDRESS,
                Address.Conversable::class.java
            )

        val CurrentActivityObserver.currentlyShowingConversation: Address.Conversable?
            get() {
                return when (val a = currentActivity.value) {
                    is ConversationActivityV2 -> a.threadAddress
                    is ConversationActivityV3 -> a.threadAddress
                    else -> null
                }
            }

        fun NotificationChannelManager.getChannelIdFor(address: Address.Conversable): String {
            return getNotificationChannelId(
                when (address) {
                    is Address.LegacyGroup,
                    is Address.Group -> NotificationChannelManager.ChannelDescription.GROUP_MESSAGES

                    is Address.Community -> NotificationChannelManager.ChannelDescription.COMMUNITY_MESSAGES
                    is Address.CommunityBlindedId,
                    is Address.Standard -> NotificationChannelManager.ChannelDescription.ONE_TO_ONE_MESSAGES
                }
            )
        }
    }
}
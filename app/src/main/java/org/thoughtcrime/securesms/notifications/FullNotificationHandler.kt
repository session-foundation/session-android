package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import org.session.libsignal.utilities.Log
import androidx.collection.MutableLongLongMap
import androidx.collection.arrayMapOf
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.squareup.phrase.Phrase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.StringSubstitutionConstants
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.messages.MessageFormatter
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.conversation.v3.ConversationActivityV3
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getIncomingMessagesSorted
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getThreadId
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.ThreadId
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.getLastSeen
import org.thoughtcrime.securesms.database.model.MessageChanges
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.database.model.ThreadChanges
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Handles notifications in [NotificationPrivacy.ShowNameAndContent] mode.
 *
 * Shows one per-thread notification with the sender's name, avatar, and full message body,
 * using [NotificationCompat.MessagingStyle]. Reactions are also included.
 */
@Singleton
class FullNotificationHandler @Inject constructor(
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
    private val avatarBitmapCache: AvatarBitmapCache,
    private val channels: NotificationChannelManager,
) {
    private val notificationManager: NotificationManagerCompat
        get() =
            NotificationManagerCompat.from(context)

    private val currentActivity get() = currentActivityObserver.currentActivity.value
    private val currentlyShowingConversation: Address.Conversable?
        get() {
            return when (val a = currentActivity) {
                is ConversationActivityV2 -> a.threadAddress
                is ConversationActivityV3 -> a.threadAddress
                else -> null
            }
        }

    private sealed interface Event

    private class ThreadUpdated(val change: ThreadChanges) : Event
    private class MessageUpdated(val change: MessageChanges) : Event
    private class ReactionUpdated(val msg: MessageId) : Event


    suspend fun process() {
        merge(
            threadDb.changeNotification.map(::ThreadUpdated),
            mmsDatabase.changeNotification.map(::MessageUpdated),
            smsDatabase.changeNotification.map(::MessageUpdated),
            reactionDatabase.changeNotification.map(::ReactionUpdated),
        ).collect(object : FlowCollector<Event> {
            private val lastPostedLatestMessageTimestampByThreadId = MutableLongLongMap()

            override suspend fun emit(value: Event) {
                // Whether this event can only update an existing notification (i.e. no action
                // if there's no existing notification, and no loud notify on the updated contents)
                var updateOnly: Boolean
                val threadId: Long
                val threadAddress: Address.Conversable
                val threadLastSeen: Long

                when (value) {
                    is ThreadUpdated -> {
                        updateOnly = true
                        threadId = value.change.id
                        threadAddress = value.change.address
                        threadLastSeen =
                            threadDb.getLastSeen(threadAddress)?.toEpochMilliseconds() ?: 0L
                        Log.d(TAG, "ThreadUpdated: threadId=$threadId, updateOnly=true")
                    }

                    is MessageUpdated -> {
                        threadId = value.change.threadId
                        threadDb.getAddressAndLastSeen(threadId)?.let {
                            threadAddress = it.first
                            threadLastSeen = it.second
                        } ?: run {
                            Log.d(TAG, "MessageUpdated: threadId=$threadId, no address/lastSeen found — skipping")
                            return
                        }

                        updateOnly = value.change.changeType != MessageChanges.ChangeType.Added ||
                                currentActivity is HomeActivity ||
                                currentlyShowingConversation == threadAddress
                        Log.d(TAG, "MessageUpdated: threadId=$threadId, changeType=${value.change.changeType}, updateOnly=$updateOnly, currentActivity=${currentActivity?.javaClass?.simpleName}, showingConversation=${currentlyShowingConversation?.debugString}")
                    }

                    is ReactionUpdated -> {
                        threadId = mmsSmsDatabase.getThreadId(value.msg) ?: run {
                            Log.d(TAG, "ReactionUpdated: no threadId found for msg=${value.msg} — skipping")
                            return
                        }
                        threadDb.getAddressAndLastSeen(threadId)?.let {
                            threadAddress = it.first
                            threadLastSeen = it.second
                        } ?: run {
                            Log.d(TAG, "ReactionUpdated: threadId=$threadId, no address/lastSeen found — skipping")
                            return
                        }

                        updateOnly = currentActivity is HomeActivity ||
                                currentlyShowingConversation == threadAddress
                        Log.d(TAG, "ReactionUpdated: threadId=$threadId, updateOnly=$updateOnly, currentActivity=${currentActivity?.javaClass?.simpleName}, showingConversation=${currentlyShowingConversation?.debugString}")
                    }
                }

                // Early exit if we don't have active notifications for updateOnly mode
                if (updateOnly && !notificationManager.containsThreadNotification(threadId)) {
                    Log.d(TAG, "threadId=$threadId: updateOnly=true but no active notification — skipping")
                    return
                }

                // Now we can look at what we have for this thread
                val threadRecipient = recipientRepository.getRecipientSync(threadAddress)
                val threadNotifyType = threadRecipient.effectiveNotifyType()

                when {
                    // If this thread is blocked...
                    threadRecipient.blocked -> {
                        Log.d(TAG, "threadId=$threadId: recipient is blocked — skipping")
                        // Do nothing, also don't need to cancel the existing notification
                        return
                    }

                    // If we aren't allowed notification...
                    threadNotifyType == NotifyType.NONE || threadRecipient.blocked -> {
                        Log.d(TAG, "threadId=$threadId: notifyType=NONE — skipping")
                        // Do nothing, also don't need to cancel the existing notification
                        return
                    }

                    // If this thread is a message request thread...
                    !threadRecipient.approved -> {
                        handleMessageRequests(
                            threadId = threadId,
                            threadLastSeen = threadLastSeen,
                            threadAddress = threadAddress,
                            threadRecipient = threadRecipient,
                            updateOnly = updateOnly,
                            lastPostedLatestMessageTimestampByThreadId = lastPostedLatestMessageTimestampByThreadId
                        )
                    }

                    // If thread notify mode is MENTION...
                    threadNotifyType == NotifyType.MENTIONS -> {
                        handleMentionsOnly(
                            threadId = threadId,
                            threadLastSeen = threadLastSeen,
                            threadRecipient = threadRecipient,
                            threadAddress = threadAddress,
                            updateOnly = updateOnly,
                            lastPostedLatestMessageTimestampByThreadId = lastPostedLatestMessageTimestampByThreadId
                        )
                    }

                    // Otherwise...
                    else -> handleFullNotification(
                        threadId = threadId,
                        threadLastSeen = threadLastSeen,
                        threadAddress = threadAddress,
                        threadRecipient = threadRecipient,
                        updateOnly = updateOnly,
                        lastPostedLatestMessageTimestampByThreadId = lastPostedLatestMessageTimestampByThreadId
                    )
                }
            }
        })
    }

    private suspend fun handleFullNotification(
        threadId: Long,
        threadLastSeen: Long,
        threadAddress: Address.Conversable,
        threadRecipient: Recipient,
        updateOnly: Boolean,
        lastPostedLatestMessageTimestampByThreadId: MutableLongLongMap
    ) {
        Log.d(TAG, "threadId=$threadId: notifyType=ALL — building full notification with messages and reactions")
        // Build out all new messages and reactions
        val newMessages =
            mmsSmsDatabase.getIncomingMessagesSorted(threadId, threadLastSeen)
        val newReactions = if (threadAddress is Address.Community) {
            // No reactions for communities are notified...
            emptyList()
        } else {
            reactionDatabase.getReactionsForThread(threadId, threadLastSeen)
        }

        Log.d(TAG, "threadId=$threadId: found ${newMessages.size} message(s), ${newReactions.size} reaction(s) since lastSeen=$threadLastSeen")

        if (newMessages.isEmpty() && newReactions.isEmpty()) {
            Log.d(TAG, "threadId=$threadId: no new content — cancelling notification")
            cancelThreadNotification(threadId)
            return
        }

        val latestMessageTimestampMs = max(
            newMessages.lastOrNull()?.dateSent ?: 0L,
            newReactions.lastOrNull()?.dateSent ?: 0L
        )

        if (latestMessageTimestampMs <= lastPostedLatestMessageTimestampByThreadId.getOrDefault(
                threadId,
                0L
            )
        ) {
            Log.d(TAG, "threadId=$threadId: latest content already notified (ts=$latestMessageTimestampMs) — skipping")
            // We've notified same content with this thread before, do nothing
            return
        }

        val messages =
            ArrayList<NotificationCompat.MessagingStyle.Message>(newMessages.size + newReactions.size)
        val personCache = arrayMapOf<Address, Person>()

        for (msg in newMessages) {
            messages += NotificationCompat.MessagingStyle.Message(
                MentionUtilities.parseAndSubstituteMentions(
                    recipientRepository = recipientRepository,
                    input = messageFormatter.formatMessageBodyForNotification(
                        context,
                        msg,
                        threadRecipient
                    ),
                    context = context
                ).text,
                msg.dateSent,
                msg.toPerson(personCache)
            )
        }

        for (reaction in newReactions) {
            messages += NotificationCompat.MessagingStyle.Message(
                Phrase.from(context, R.string.emojiReactsNotification)
                    .put(StringSubstitutionConstants.EMOJI_KEY, reaction.emoji)
                    .format(),
                reaction.dateSent,
                reaction.toPerson(personCache)
            )
        }

        messages.sortBy { it.timestamp }

        lastPostedLatestMessageTimestampByThreadId.put(threadId, latestMessageTimestampMs)
        postOrUpdateNotification(
            threadAddress = threadAddress,
            threadRecipient = threadRecipient,
            threadId = threadId,
            latestMessageTimestampMs = latestMessageTimestampMs,
            messages = messages,
            canReply = true,
            silent = updateOnly
        )
    }

    private suspend fun handleMentionsOnly(
        threadId: Long,
        threadLastSeen: Long,
        threadRecipient: Recipient,
        threadAddress: Address.Conversable,
        updateOnly: Boolean,
        lastPostedLatestMessageTimestampByThreadId: MutableLongLongMap
    ) {
        Log.d(TAG, "threadId=$threadId: notifyType=MENTIONS — filtering for self-mention messages only")
        // Actually build out the messages first given we have to go through the
        // contents anyway. Note that we don't care about reactions for MENTIONS
        // only mode.
        val personCache by lazy { arrayMapOf<Address, Person>() }
        val messages =
            mmsSmsDatabase.getIncomingMessagesSorted(threadId, threadLastSeen)
                .mapNotNull { m ->
                    val mentions = MentionUtilities.parseAndSubstituteMentions(
                        recipientRepository = recipientRepository,
                        input = messageFormatter.formatMessageBodyForNotification(
                            context,
                            m,
                            threadRecipient
                        ),
                        context = context
                    )

                    if (!mentions.mentions.any { it.isSelf }) {
                        return@mapNotNull null
                    }

                    NotificationCompat.MessagingStyle.Message(
                        mentions.text,
                        m.dateSent,
                        m.toPerson(personCache)
                    )
                }

        if (messages.isEmpty()) {
            Log.d(TAG, "threadId=$threadId: MENTIONS — no self-mention messages found — cancelling notification")
            cancelThreadNotification(threadId)
            return
        }

        val latestMessageSent = messages.last().timestamp
        when {
            messages.isEmpty() -> {
                Log.d(TAG, "threadId=$threadId: MENTIONS — no self-mention messages found — cancelling notification")
                cancelThreadNotification(threadId)
            }

            latestMessageSent <= lastPostedLatestMessageTimestampByThreadId.getOrDefault(
                threadId,
                0L
            ) -> {
                Log.d(TAG, "threadId=$threadId: MENTIONS — latest mention already notified (ts=$latestMessageSent) — skipping")
                // No need to notify again
            }

            else -> {
                lastPostedLatestMessageTimestampByThreadId.put(
                    threadId,
                    latestMessageSent
                )
                postOrUpdateNotification(
                    threadAddress = threadAddress,
                    threadRecipient = threadRecipient,
                    threadId = threadId,
                    latestMessageTimestampMs = latestMessageSent,
                    messages = messages,
                    canReply = true,
                    silent = updateOnly,
                )
            }
        }
    }

    private suspend fun handleMessageRequests(
        threadId: Long,
        threadLastSeen: Long,
        threadAddress: Address.Conversable,
        threadRecipient: Recipient,
        updateOnly: Boolean,
        lastPostedLatestMessageTimestampByThreadId: MutableLongLongMap,
    ) {
        Log.d(TAG, "threadId=$threadId: message request thread — showing generic 'new message request' notification")
        // The only thing we notify user for this convo
        // is "You have a new message request",
        // so only we need to find out new messages since lastSeen or lastPosted
        val newMessage = mmsSmsDatabase.getIncomingMessagesSorted(
            threadId,
            startMsExclusive = max(
                threadLastSeen,
                lastPostedLatestMessageTimestampByThreadId.getOrDefault(
                    threadId,
                    0L
                )
            )
        ).lastOrNull()

        if (newMessage == null) {
            Log.d(TAG, "threadId=$threadId: no new message request messages — cancelling notification")
            cancelThreadNotification(threadId)
            return
        }

        lastPostedLatestMessageTimestampByThreadId.put(threadId, newMessage.dateSent)
        postOrUpdateNotification(
            threadAddress = threadAddress,
            threadRecipient = threadRecipient,
            threadId = threadId,
            latestMessageTimestampMs = newMessage.dateSent,
            messages = listOf(
                NotificationCompat.MessagingStyle.Message(
                    context.getText(R.string.messageRequestsNew),
                    newMessage.dateSent,
                    newMessage.toPerson(null)
                )
            ),
            canReply = false,
            silent = updateOnly
        )
    }

    private fun cancelThreadNotification(threadId: Long) {
        notificationManager.cancel(threadTag(threadId), NotificationId.MESSAGE_THREAD)
    }

    private fun NotificationManagerCompat.containsThreadNotification(threadId: Long): Boolean {
        return activeNotifications.any {
            it.tag == threadTag(threadId) && it.id == NotificationId.MESSAGE_THREAD
        }
    }

    private suspend fun Recipient.buildPerson(): Person {
        return Person.Builder()
            .setName(displayName())
            .setIcon(getIcon(avatarUtils.getUIDataFromRecipient(this)))
            .build()
    }

    private suspend fun MessageRecord.toPerson(personCache: MutableMap<Address, Person>?): Person {
        if (personCache == null) {
            return individualRecipient.buildPerson()
        }

        return personCache.getOrPut(individualRecipient.address) {
            individualRecipient.buildPerson()
        }
    }

    private suspend fun ReactionRecord.toPerson(personCache: MutableMap<Address, Person>): Person {
        val address = author.toAddress()
        return personCache.getOrPut(address) {
            recipientRepository.getRecipientSync(address).buildPerson()
        }
    }

    private suspend fun postOrUpdateNotification(
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
        private const val TAG = "FullNotificationHandler"
    }
}

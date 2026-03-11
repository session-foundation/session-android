package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.collection.MutableLongLongMap
import androidx.collection.arrayMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.squareup.phrase.Phrase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.messages.MessageFormatter
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getIncomingMessagesSorted
import org.thoughtcrime.securesms.database.MmsSmsDatabaseExt.getThreadId
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.getLastSeen
import org.thoughtcrime.securesms.database.model.MessageChanges
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.database.model.ThreadChanges
import org.thoughtcrime.securesms.home.HomeActivity
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
    @ApplicationContext context: Context,
    private val threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    recipientRepository: RecipientRepository,
    currentActivityObserver: CurrentActivityObserver,
    private val reactionDatabase: ReactionDatabase,
    private val messageFormatter: MessageFormatter,
    avatarUtils: AvatarUtils,
    avatarBitmapCache: AvatarBitmapCache,
    channels: NotificationChannelManager,
) : ThreadBasedNotificationHandler(
    context = context,
    currentActivityObserver = currentActivityObserver,
    avatarUtils = avatarUtils,
    channels = channels,
    recipientRepository = recipientRepository,
    avatarBitmapCache = avatarBitmapCache
) {

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

        doNotify(
            newMessages,
            newReactions,
            threadRecipient,
            lastPostedLatestMessageTimestampByThreadId,
            threadId,
            threadAddress,
            updateOnly
        )
    }

    private suspend fun doNotify(
        newMessages: List<MessageRecord>,
        newReactions: List<ReactionRecord>,
        threadRecipient: Recipient,
        lastPostedLatestMessageTimestampByThreadId: MutableLongLongMap,
        threadId: Long,
        threadAddress: Address.Conversable,
        updateOnly: Boolean
    ) {
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
        Log.d(TAG, "threadId=$threadId: notifyType=MENTIONS ")
        // The rule for a mentions only thread is: we'll notify the user only if the user is MENTIONED in
        // the newly added message, but once we are notifying the user, we show all new messages,
        // not only the mentioned ones. i.e. the mention is only a trigger

        val allNewMessages = mmsSmsDatabase.getIncomingMessagesSorted(threadId, threadLastSeen)

        if (allNewMessages.isEmpty()) {
            Log.d(TAG, "threadId=$threadId: no new messages — cancelling notification")
            cancelThreadNotification(threadId)
            return
        }

        // Now we need to inspect the unnotified new messages for any mentions
        val lastPostedLatestMessageTimestamp = lastPostedLatestMessageTimestampByThreadId.getOrDefault(threadId, 0L)
        val unnotifiedMessagesContainMentions = allNewMessages.any { it.dateSent > lastPostedLatestMessageTimestamp &&
                MentionUtilities.mentionsMe(it.body, recipientRepository) }

        if (!unnotifiedMessagesContainMentions) {
            Log.d(TAG, "threadId=$threadId: no new self-mention messages, ignoring")
            return
        }

        doNotify(
            newMessages = allNewMessages,
            newReactions = reactionDatabase.getReactionsForThread(threadId, threadLastSeen),
            threadRecipient = threadRecipient,
            lastPostedLatestMessageTimestampByThreadId = lastPostedLatestMessageTimestampByThreadId,
            threadId = threadId,
            threadAddress = threadAddress,
            updateOnly = updateOnly
        )
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


    companion object {
        private const val TAG = "FullNotificationHandler"
    }
}

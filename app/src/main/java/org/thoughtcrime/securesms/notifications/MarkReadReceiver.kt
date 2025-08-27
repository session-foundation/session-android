package org.thoughtcrime.securesms.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.MessagingModuleConfiguration.Companion.shared
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.sending_receiving.MessageSender.send
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeAPI.nowWithOffset
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.TextSecurePreferences.Companion.isReadReceiptsEnabled
import org.session.libsession.utilities.associateByNotNull
import org.session.libsession.utilities.isGroupOrCommunity
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.disappearingmessages.ExpiryType
import org.thoughtcrime.securesms.database.MarkedMessageInfo
import org.thoughtcrime.securesms.database.model.content.DisappearingMessageUpdate
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import javax.inject.Inject

@AndroidEntryPoint
class MarkReadReceiver : BroadcastReceiver() {
    @Inject
    lateinit var storage: StorageProtocol

    @Inject
    lateinit var clock: SnodeClock

    override fun onReceive(context: Context, intent: Intent) {
        if (CLEAR_ACTION != intent.action) return
        val threadIds = intent.getLongArrayExtra(THREAD_IDS_EXTRA) ?: return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(NOTIFICATION_ID_EXTRA, -1))
        GlobalScope.launch {
            val currentTime = clock.currentTimeMills()
            threadIds.forEach {
                Log.i(TAG, "Marking as read: $it")
                storage.markConversationAsRead(
                    threadId = it,
                    lastSeenTime = currentTime,
                    force = true
                )
            }
        }
    }

    companion object {
        private val TAG = MarkReadReceiver::class.java.simpleName
        const val CLEAR_ACTION = "network.loki.securesms.notifications.CLEAR"
        const val THREAD_IDS_EXTRA = "thread_ids"
        const val NOTIFICATION_ID_EXTRA = "notification_id"

        @JvmStatic
        fun process(
            context: Context,
            markedReadMessages: List<MarkedMessageInfo>
        ) {
            if (markedReadMessages.isEmpty()) return

            sendReadReceipts(context, markedReadMessages)

            val mmsSmsDatabase = DatabaseComponent.get(context).mmsSmsDatabase()

            val threadDb = DatabaseComponent.get(context).threadDatabase()

            // start disappear after read messages except TimerUpdates in groups.
            markedReadMessages
                .asSequence()
                .filter { it.expiryType == ExpiryType.AFTER_READ }
                .filter { mmsSmsDatabase.getMessageById(it.expirationInfo.id)?.run {
                    (messageContent is DisappearingMessageUpdate)
                            && threadDb.getRecipientForThreadId(threadId)?.isGroupOrCommunity == true } == false
                }
                .forEach {
                    val db = if (it.expirationInfo.id.mms) {
                        DatabaseComponent.get(context).mmsDatabase()
                    } else {
                        DatabaseComponent.get(context).smsDatabase()
                    }

                    db.markExpireStarted(it.expirationInfo.id.id, nowWithOffset)
                }

            hashToDisappearAfterReadMessage(context, markedReadMessages)?.let { hashToMessages ->
                GlobalScope.launch {
                    try {
                        shortenExpiryOfDisappearingAfterRead(hashToMessages)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch updated expiries and schedule deletion", e)
                    }
                }
            }
        }

        private fun hashToDisappearAfterReadMessage(
            context: Context,
            markedReadMessages: List<MarkedMessageInfo>
        ): Map<String, MarkedMessageInfo>? {
            val loki = DatabaseComponent.get(context).lokiMessageDatabase()

            return markedReadMessages
                .filter { it.expiryType == ExpiryType.AFTER_READ }
                .associateByNotNull { it.expirationInfo.run { loki.getMessageServerHash(id) } }
                .takeIf { it.isNotEmpty() }
        }

        private fun shortenExpiryOfDisappearingAfterRead(
            hashToMessage: Map<String, MarkedMessageInfo>
        ) {
            hashToMessage.entries
                .groupBy(
                    keySelector =  { it.value.expirationInfo.expiresIn },
                    valueTransform = { it.key }
                ).forEach { (expiresIn, hashes) ->
                    SnodeAPI.alterTtl(
                        messageHashes = hashes,
                        newExpiry = nowWithOffset + expiresIn,
                        auth = checkNotNull(shared.storage.userAuth) { "No authorized user" },
                        shorten = true
                    )
                }
        }

        private val Recipient.shouldSendReadReceipt: Boolean
            get() = when (data) {
                is RecipientData.Contact -> approved && !blocked
                is RecipientData.Generic -> !isGroupOrCommunityRecipient && !blocked
                else -> false
            }

        private fun sendReadReceipts(
            context: Context,
            markedReadMessages: List<MarkedMessageInfo>
        ) {
            if (!isReadReceiptsEnabled(context)) return

            val recipientRepository = MessagingModuleConfiguration.shared.recipientRepository

            markedReadMessages.map { it.syncMessageId }
                .filter { recipientRepository.getRecipientSync(it.address)?.shouldSendReadReceipt == true }
                .groupBy { it.address }
                .forEach { (address, messages) ->
                    messages.map { it.timetamp }
                        .let(::ReadReceipt)
                        .apply { sentTimestamp = nowWithOffset }
                        .let { send(it, address) }
                }
        }
    }
}

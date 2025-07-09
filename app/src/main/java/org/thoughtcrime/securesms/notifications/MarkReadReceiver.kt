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
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.TextSecurePreferences.Companion.isReadReceiptsEnabled
import org.session.libsession.utilities.associateByNotNull
import org.session.libsession.utilities.recipients.BasicRecipient
import org.session.libsession.utilities.recipients.isGroupOrCommunityRecipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.conversation.disappearingmessages.ExpiryType
import org.thoughtcrime.securesms.database.ExpirationInfo
import org.thoughtcrime.securesms.database.MarkedMessageInfo
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

        val messageExpirationManager = SSKEnvironment.shared.messageExpirationManager

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
                    isExpirationTimerUpdate && threadDb.getRecipientForThreadId(threadId)?.isGroupOrCommunity == true } == false
                }
                .forEach { messageExpirationManager.startExpiringNow(it.expirationInfo.id) }

            hashToDisappearAfterReadMessage(context, markedReadMessages)?.let { hashToMessages ->
                GlobalScope.launch {
                    try {
                        fetchUpdatedExpiriesAndScheduleDeletion(context, hashToMessages)
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

        private val BasicRecipient.shouldSendReadReceipt: Boolean
            get() = when (this) {
                is BasicRecipient.Contact -> approved && !blocked
                is BasicRecipient.Generic -> !isGroupOrCommunityRecipient && !blocked
                else -> false
            }

        private fun sendReadReceipts(
            context: Context,
            markedReadMessages: List<MarkedMessageInfo>
        ) {
            if (!isReadReceiptsEnabled(context)) return

            val recipientRepository = MessagingModuleConfiguration.shared.recipientRepository

            markedReadMessages.map { it.syncMessageId }
                .filter { recipientRepository.getBasicRecipientFast(it.address)?.shouldSendReadReceipt == true }
                .groupBy { it.address }
                .forEach { (address, messages) ->
                    messages.map { it.timetamp }
                        .let(::ReadReceipt)
                        .apply { sentTimestamp = nowWithOffset }
                        .let { send(it, address) }
                }
        }

        private suspend fun fetchUpdatedExpiriesAndScheduleDeletion(
            context: Context,
            hashToMessage: Map<String, MarkedMessageInfo>
        ) {
            @Suppress("UNCHECKED_CAST")
            val expiries = SnodeAPI.getExpiries(hashToMessage.keys.toList(), shared.storage.userAuth!!).await()["expiries"] as Map<String, Long>
            hashToMessage.forEach { (hash, info) -> expiries[hash]?.let { scheduleDeletion(context, info.expirationInfo, it - info.expirationInfo.expireStarted) } }
        }

        private fun scheduleDeletion(
            context: Context,
            expirationInfo: ExpirationInfo,
            expiresIn: Long = expirationInfo.expiresIn
        ) {
            if (expiresIn == 0L) return

            val now = nowWithOffset

            val expireStarted = expirationInfo.expireStarted

            if (expirationInfo.isDisappearAfterRead() && expireStarted == 0L || now < expireStarted) {
                val db = DatabaseComponent.get(context).run { if (expirationInfo.id.mms) mmsDatabase() else smsDatabase() }
                db.markExpireStarted(expirationInfo.id.id, now)
            }

            ApplicationContext.getInstance(context).expiringMessageManager.get().scheduleDeletion(
                expirationInfo.id,
                now,
                expiresIn
            )
        }
    }
}

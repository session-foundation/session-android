package org.thoughtcrime.securesms.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.TextSecurePreferences.Companion.isReadReceiptsEnabled
import org.session.libsession.utilities.associateByNotNull
import org.session.libsession.utilities.isGroupOrCommunity
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.disappearingmessages.ExpiryType
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MarkedMessageInfo
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.content.DisappearingMessageUpdate
import javax.inject.Inject

class MarkReadProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val recipientRepository: RecipientRepository,
    private val messageSender: MessageSender,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val threadDb: ThreadDatabase,
    private val storage: StorageProtocol,
    private val snodeClock: SnodeClock,
    private val lokiMessageDatabase: LokiMessageDatabase,
) {
    fun process(
        markedReadMessages: List<MarkedMessageInfo>
    ) {
        if (markedReadMessages.isEmpty()) return

        sendReadReceipts(
            markedReadMessages = markedReadMessages
        )


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
                    mmsDatabase
                } else {
                    smsDatabase
                }

                db.markExpireStarted(it.expirationInfo.id.id, snodeClock.currentTimeMills())
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
        return markedReadMessages
            .filter { it.expiryType == ExpiryType.AFTER_READ }
            .associateByNotNull { it.expirationInfo.run { lokiMessageDatabase.getMessageServerHash(id) } }
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
                    newExpiry = snodeClock.currentTimeMills() + expiresIn,
                    auth = checkNotNull(storage.userAuth) { "No authorized user" },
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
        markedReadMessages: List<MarkedMessageInfo>
    ) {
        if (!isReadReceiptsEnabled(context)) return

        markedReadMessages.map { it.syncMessageId }
            .filter { recipientRepository.getRecipientSync(it.address).shouldSendReadReceipt }
            .groupBy { it.address }
            .forEach { (address, messages) ->
                messages.map { it.timetamp }
                    .let(::ReadReceipt)
                    .apply { sentTimestamp = snodeClock.currentTimeMills() }
                    .let { messageSender.send(it, address) }
            }
    }

    companion object {
        private const val TAG = "MarkReadProcessor"
    }
}
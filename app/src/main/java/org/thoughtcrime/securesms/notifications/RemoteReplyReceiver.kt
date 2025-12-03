/*
 * Copyright (C) 2016 Open Whisper Systems
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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.libsession_util.util.ExpiryMode.AfterSend
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.mms.MmsException
import org.thoughtcrime.securesms.pro.ProStatusManager
import javax.inject.Inject

/**
 * Get the response text from the Wearable Device and sends an message as a reply
 */
@AndroidEntryPoint
class RemoteReplyReceiver : BroadcastReceiver() {
    @Inject
    lateinit var threadDatabase: ThreadDatabase

    @Inject
    lateinit var mmsDatabase: MmsDatabase

    @Inject
    lateinit var smsDatabase: SmsDatabase

    @Inject
    lateinit var storage: Storage

    @Inject
    lateinit var messageNotifier: MessageNotifier

    @Inject
    lateinit var clock: SnodeClock

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var markReadProcessor: MarkReadProcessor

    @Inject
    lateinit var messageSender: MessageSender


    @Inject
    lateinit var proStatusManager: ProStatusManager

    @SuppressLint("StaticFieldLeak")
    override fun onReceive(context: Context, intent: Intent) {
        if (REPLY_ACTION != intent.getAction()) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent)

        if (remoteInput == null) return

        val address = intent.getParcelableExtra<Address?>(ADDRESS_EXTRA)
        val replyMethod = intent.getSerializableExtra(REPLY_METHOD) as ReplyMethod?
        val responseText = remoteInput.getCharSequence(DefaultMessageNotifier.EXTRA_REMOTE_REPLY)

        if (address == null) throw AssertionError("No address specified")
        if (replyMethod == null) throw AssertionError("No reply method specified")

        if (responseText != null) {
            object : AsyncTask<Void?, Void?, Void?>() {
                override fun doInBackground(vararg params: Void?): Void? {
                    val threadId = threadDatabase.getOrCreateThreadIdFor(address)
                    val message = VisibleMessage()
                    message.sentTimestamp = clock.currentTimeMills()
                    message.text = responseText.toString()
                    proStatusManager.addProFeatures(message)
                    val expiryMode = recipientRepository.getRecipientSync(address).expiryMode

                    val expiresInMillis = expiryMode.expiryMillis
                    val expireStartedAt: Long =
                        (if (expiryMode is AfterSend) message.sentTimestamp else 0L)!!
                    when (replyMethod) {
                        ReplyMethod.GroupMessage -> {
                            val reply = OutgoingMediaMessage(
                                message = message,
                                recipient = address,
                                attachments = listOf(),
                                outgoingQuote = null,
                                linkPreview = null,
                                expiresInMillis = expiresInMillis,
                                expireStartedAt = 0
                            )
                            try {
                                message.id = MessageId(
                                    mmsDatabase.insertMessageOutbox(
                                        message = reply,
                                        threadId = threadId,
                                        forceSms = false,
                                        runThreadUpdate = true
                                    ), true
                                )
                                messageSender.send(message, address)
                            } catch (e: MmsException) {
                                Log.w(TAG, e)
                            }
                        }

                        ReplyMethod.SecureMessage -> {
                            val reply = OutgoingTextMessage(
                                message = message,
                                recipient = address,
                                expiresInMillis = expiresInMillis,
                                expireStartedAtMillis = expireStartedAt
                            )
                            message.id = MessageId(
                                smsDatabase.insertMessageOutbox(
                                    threadId,
                                    reply,
                                    false,
                                    System.currentTimeMillis(),
                                    true
                                ), false
                            )
                            messageSender.send(message, address)
                        }
                    }

                    val messageIds = threadDatabase.setRead(threadId, true)

                    messageNotifier.updateNotification(context)
                    markReadProcessor.process(messageIds)

                    return null
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    companion object {
        val TAG: String = RemoteReplyReceiver::class.java.getSimpleName()
        const val REPLY_ACTION: String = "network.loki.securesms.notifications.WEAR_REPLY"
        const val ADDRESS_EXTRA: String = "address"
        const val REPLY_METHOD: String = "reply_method"
    }
}

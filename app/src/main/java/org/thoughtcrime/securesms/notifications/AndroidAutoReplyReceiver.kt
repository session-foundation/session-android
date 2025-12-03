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
import org.session.libsession.snode.SnodeAPI.nowWithOffset
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.isGroupOrCommunity
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.mms.MmsException
import org.thoughtcrime.securesms.pro.ProStatusManager
import javax.inject.Inject

/**
 * Get the response text from the Android Auto and sends an message as a reply
 */
@AndroidEntryPoint
class AndroidAutoReplyReceiver : BroadcastReceiver() {
    @Inject
    lateinit var threadDatabase: ThreadDatabase

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var mmsDatabase: MmsDatabase

    @Inject
    lateinit var smsDatabase: SmsDatabase

    @Inject
    lateinit var messageNotifier: MessageNotifier

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
        val threadId = intent.getLongExtra(THREAD_ID_EXTRA, -1)
        val responseText = getMessageText(intent)

        if (responseText != null) {
            object : AsyncTask<Void?, Void?, Void?>() {
                override fun doInBackground(vararg params: Void?): Void? {
                    val replyThreadId: Long

                    if (threadId == -1L) {
                        replyThreadId = threadDatabase.getOrCreateThreadIdFor(address)
                    } else {
                        replyThreadId = threadId
                    }

                    val message = VisibleMessage()
                    message.text = responseText.toString()
                    proStatusManager.addProFeatures(message)
                    message.sentTimestamp = nowWithOffset
                    messageSender.send(message, address!!)
                    val expiryMode = recipientRepository.getRecipientSync(address).expiryMode
                    val expiresInMillis = expiryMode.expiryMillis
                    val expireStartedAt: Long =
                        (if (expiryMode is AfterSend) message.sentTimestamp!! else 0L)

                    if (address.isGroupOrCommunity) {
                        Log.w("AndroidAutoReplyReceiver", "GroupRecipient, Sending media message")
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
                            mmsDatabase.insertMessageOutbox(
                                message = reply,
                                threadId = replyThreadId,
                                forceSms = false,
                                runThreadUpdate = true
                            )
                        } catch (e: MmsException) {
                            Log.w(TAG, e)
                        }
                    } else {
                        Log.w("AndroidAutoReplyReceiver", "Sending regular message ")
                        val reply = OutgoingTextMessage(
                            message = message,
                            recipient = address,
                            expiresInMillis = expiresInMillis,
                            expireStartedAtMillis = expireStartedAt
                        )
                        smsDatabase.insertMessageOutbox(
                            replyThreadId,
                            reply,
                            false,
                            nowWithOffset,
                            true
                        )
                    }

                    val messageIds = threadDatabase.setRead(replyThreadId, true)

                    messageNotifier.updateNotification(context)
                    markReadProcessor.process(messageIds)

                    return null
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    private fun getMessageText(intent: Intent): CharSequence? {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput != null) {
            return remoteInput.getCharSequence(VOICE_REPLY_KEY)
        }
        return null
    }

    companion object {
        val TAG: String = AndroidAutoReplyReceiver::class.java.getSimpleName()
        const val REPLY_ACTION: String = "network.loki.securesms.notifications.ANDROID_AUTO_REPLY"
        const val ADDRESS_EXTRA: String = "car_address"
        const val VOICE_REPLY_KEY: String = "car_voice_reply_key"
        const val THREAD_ID_EXTRA: String = "car_reply_thread_id"
    }
}

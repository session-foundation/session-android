package org.thoughtcrime.securesms.webrtc

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.ANSWER
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.END_CALL
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.ICE_CANDIDATES
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.OFFER
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.PRE_OFFER
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.PROVISIONAL_ANSWER
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.calls.WebRtcCallActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.service.WebRtcCallService
import org.webrtc.IceCandidate
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants


class CallMessageProcessor(private val context: Context, private val textSecurePreferences: TextSecurePreferences, lifecycle: Lifecycle, private val storage: StorageProtocol) {



    companion object {
        private const val VERY_EXPIRED_TIME = 15 * 60 * 1000L



        // While fine if the app is in the foreground, you cannot do this in modern Android if the
        // device is locked (i.e., if you get a call when the device is locked & attempt start the
        // foreground service) it will throw an error like:
        //      Unable to start CallMessage intent: startForegroundService() not allowed due to mAllowStartForeground false:
        //      service network.loki.messenger/org.thoughtcrime.securesms.service.WebRtcCallService
        fun safeStartForegroundService(context: Context, intent: Intent) {
            // Attempt to start the call service..
            try { context.startService(intent) }
            catch(e: Exception) {
                // ..however due to tightened restrictions in Android 12 and above this will not
                // work (BackgroundServiceStartNotAllowedException) if the device is asleep / locked
                // so we have to wake the device first and then try to start it as a foreground service.
                // Note: Attempting to start a foreground service while the device is asleep / locked
                // will also cause an exception.
                wakeUpDeviceIfLocked(context)
                try { ContextCompat.startForegroundService(context, intent) }
                catch (e2: Exception) {
                    Log.e("Loki", "Unable to start CallMessage intent: ${e2.message}")
                }
            }
        }

        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                "WakeUpChannelID", // CHANNEL_ID,
                "WakeUpChannelName", //CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming calls"
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        // Wake the device up if it's asleep / locked - used when we receive an incoming call
        private fun wakeUpDeviceIfLocked(context: Context) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenAwake = powerManager.isInteractive
            if (!isScreenAwake) {
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "${NonTranslatableStringConstants.APP_NAME}:WakeLock"
                )

                // We only need the wake lock briefly
                wakeLock.acquire(3000)
            }
        }
    }

    init {
        lifecycle.coroutineScope.launch(IO) {
            while (isActive) {
                val nextMessage = WebRtcUtils.SIGNAL_QUEUE.receive()
                Log.d("Loki", nextMessage.type?.name ?: "CALL MESSAGE RECEIVED")
                val sender = nextMessage.sender ?: continue
                val approvedContact = Recipient.from(context, Address.fromSerialized(sender), false).isApproved
                Log.i("Loki", "Contact is approved?: $approvedContact")
                if (!approvedContact && storage.getUserPublicKey() != sender) continue

                // If the user has not enabled voice/video calls or if the user has not granted audio/microphone permissions
                if (
                    !textSecurePreferences.isCallNotificationsEnabled() ||
                        !Permissions.hasAll(context, Manifest.permission.RECORD_AUDIO)
                    ) {
                    Log.d("Loki","Dropping call message if call notifications disabled")
                    if (nextMessage.type != PRE_OFFER) continue
                    val sentTimestamp = nextMessage.sentTimestamp ?: continue
                    insertMissedCall(sender, sentTimestamp)
                    continue
                }

                val isVeryExpired = (nextMessage.sentTimestamp?:0) + VERY_EXPIRED_TIME < SnodeAPI.nowWithOffset
                if (isVeryExpired) {
                    Log.e("Loki", "Dropping very expired call message")
                    continue
                }

                when (nextMessage.type) {
                    OFFER -> incomingCall(nextMessage)
                    ANSWER -> incomingAnswer(nextMessage)
                    END_CALL -> incomingHangup(nextMessage)
                    ICE_CANDIDATES -> handleIceCandidates(nextMessage)
                    PRE_OFFER -> incomingPreOffer(nextMessage)
                    PROVISIONAL_ANSWER, null -> {} // TODO: if necessary
                }
            }
        }
    }

    private fun insertMissedCall(sender: String, sentTimestamp: Long) {
        val currentUserPublicKey = storage.getUserPublicKey()
        if (sender == currentUserPublicKey) return // don't insert a "missed" due to call notifications disabled if it's our own sender
        storage.insertCallMessage(sender, CallMessageType.CALL_MISSED, sentTimestamp)
    }

    private fun incomingHangup(callMessage: CallMessage) {
        val callId = callMessage.callId ?: return
        val hangupIntent = WebRtcCallService.remoteHangupIntent(context, callId)
        safeStartForegroundService(context, hangupIntent)
    }

    private fun incomingAnswer(callMessage: CallMessage) {
        val recipientAddress = callMessage.sender ?: return
        val callId = callMessage.callId ?: return
        val sdp = callMessage.sdps.firstOrNull() ?: return
        val answerIntent = WebRtcCallService.incomingAnswer(
                context = context,
                address = Address.fromSerialized(recipientAddress),
                sdp = sdp,
                callId = callId
        )

        safeStartForegroundService(context, answerIntent)
    }

    private fun handleIceCandidates(callMessage: CallMessage) {
        val callId = callMessage.callId ?: return
        val sender = callMessage.sender ?: return

        val iceCandidates = callMessage.iceCandidates()
        if (iceCandidates.isEmpty()) return

        val iceIntent = WebRtcCallService.iceCandidates(
                context = context,
                iceCandidates = iceCandidates,
                callId = callId,
                address = Address.fromSerialized(sender)
        )
        safeStartForegroundService(context, iceIntent)
    }

    private fun incomingPreOffer(callMessage: CallMessage) {
        // handle notification state
        val recipientAddress = callMessage.sender ?: return
        val callId = callMessage.callId ?: return
        val incomingIntent = WebRtcCallService.preOffer(
                context = context,
                address = Address.fromSerialized(recipientAddress),
                callId = callId,
                callTime = callMessage.sentTimestamp!!
        )
        safeStartForegroundService(context, incomingIntent)
    }

    private fun incomingCall(callMessage: CallMessage) {
        val recipientAddress = callMessage.sender ?: return
        val callId = callMessage.callId ?: return
        val sdp = callMessage.sdps.firstOrNull() ?: return
        val incomingIntent = WebRtcCallService.incomingCall(
                context = context,
                address = Address.fromSerialized(recipientAddress),
                sdp = sdp,
                callId = callId,
                callTime = callMessage.sentTimestamp!!
        )
        safeStartForegroundService(context, incomingIntent)
    }

    private fun CallMessage.iceCandidates(): List<IceCandidate> {
        if (sdpMids.size != sdpMLineIndexes.size || sdpMLineIndexes.size != sdps.size) {
            return listOf() // uneven sdp numbers
        }
        val candidateSize = sdpMids.size
        return (0 until candidateSize).map { i ->
            IceCandidate(sdpMids[i], sdpMLineIndexes[i], sdps[i])
        }
    }

}
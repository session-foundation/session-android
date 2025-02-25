package org.thoughtcrime.securesms.webrtc

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
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
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.ANSWER
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.END_CALL
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.ICE_CANDIDATES
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.OFFER
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.PRE_OFFER
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.PROVISIONAL_ANSWER
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.ThreadUtils
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.service.WebRtcCallService
import org.webrtc.IceCandidate

class CallMessageProcessor(private val context: Context, private val textSecurePreferences: TextSecurePreferences, lifecycle: Lifecycle, private val storage: StorageProtocol) {

    companion object {
        private const val TAG = "CallMessageProcessor"
        private const val VERY_EXPIRED_TIME = 15 * 60 * 1000L

        fun safeStartForegroundService(context: Context, intent: Intent) {
            // Wake up the device (if required) before attempting to start any services - otherwise on Android 12 and above we get
            // a BackgroundServiceStartNotAllowedException such as:
            //      Unable to start CallMessage intent: startForegroundService() not allowed due to mAllowStartForeground false:
            //      service network.loki.messenger/org.thoughtcrime.securesms.service.WebRtcCallService
            (context as ApplicationContext).wakeUpDeviceAndDismissKeyguardIfRequired()

            // Attempt to start the call service..
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("Loki", "Unable to start service: ${e.message}", e)
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Unable to start CallMessage intent: ${e2.message}", e2)
                }
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
        val recipientAddress = callMessage.sender ?: return Log.w(TAG, "Cannot answer incoming call without sender")
        val callId = callMessage.callId ?: return Log.w(TAG, "Cannot answer incoming call without callId" )
        val sdp = callMessage.sdps.firstOrNull() ?: return Log.w(TAG, "Cannot answer incoming call without sdp")
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
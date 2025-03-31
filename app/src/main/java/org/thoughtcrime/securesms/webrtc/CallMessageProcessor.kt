package org.thoughtcrime.securesms.webrtc

import android.Manifest
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.GlobalScope
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
import org.thoughtcrime.securesms.permissions.Permissions
import org.webrtc.IceCandidate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallMessageProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textSecurePreferences: TextSecurePreferences,
    private val storage: StorageProtocol,
    private val webRtcBridge: WebRtcCallBridge
) {

    companion object {
        private const val TAG = "CallMessageProcessor"
        private const val VERY_EXPIRED_TIME = 15 * 60 * 1000L
    }

    init {
        GlobalScope.launch(IO) {
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
        Log.d("", "CallMessageProcessor: incomingHangup")
        val callId = callMessage.callId ?: return
        webRtcBridge.handleRemoteHangup(callId)
    }

    private fun incomingAnswer(callMessage: CallMessage) {
        Log.d("", "CallMessageProcessor: incomingAnswer")
        val recipientAddress = callMessage.sender ?: return Log.w(TAG, "Cannot answer incoming call without sender")
        val callId = callMessage.callId ?: return Log.w(TAG, "Cannot answer incoming call without callId" )
        val sdp = callMessage.sdps.firstOrNull() ?: return Log.w(TAG, "Cannot answer incoming call without sdp")

        webRtcBridge.handleAnswerIncoming(
            address = Address.fromSerialized(recipientAddress),
            sdp = sdp,
            callId = callId
        )
    }

    private fun handleIceCandidates(callMessage: CallMessage) {
        Log.d("", "CallMessageProcessor: handleIceCandidates")
        val callId = callMessage.callId ?: return
        val sender = callMessage.sender ?: return

        val iceCandidates = callMessage.iceCandidates()
        if (iceCandidates.isEmpty()) return

        webRtcBridge.handleRemoteIceCandidate(
                iceCandidates = iceCandidates,
                callId = callId
        )
    }

    private fun incomingPreOffer(callMessage: CallMessage) {
        // handle notification state
        Log.d("", "CallMessageProcessor: incomingPreOffer")
        val recipientAddress = callMessage.sender ?: return
        val callId = callMessage.callId ?: return

        webRtcBridge.handlePreOffer(
            address = Address.fromSerialized(recipientAddress),
            callId = callId,
            callTime = callMessage.sentTimestamp!!
        )
    }

    private fun incomingCall(callMessage: CallMessage) {
        Log.d("", "CallMessageProcessor: incomingCall")

        val recipientAddress = callMessage.sender ?: return
        val callId = callMessage.callId ?: return
        val sdp = callMessage.sdps.firstOrNull() ?: return

        webRtcBridge.onIncomingCall(
            address = Address.fromSerialized(recipientAddress),
            sdp = sdp,
            callId = callId,
            callTime = callMessage.sentTimestamp!!
        )
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
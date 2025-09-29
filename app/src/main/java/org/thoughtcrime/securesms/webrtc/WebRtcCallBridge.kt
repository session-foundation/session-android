package org.thoughtcrime.securesms.webrtc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.FutureTaskListener
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.notifications.BackgroundPollWorker
import org.thoughtcrime.securesms.service.CallForegroundService
import org.thoughtcrime.securesms.util.NetworkConnectivity
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.TYPE_ESTABLISHED
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.TYPE_INCOMING_CONNECTING
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.TYPE_INCOMING_PRE_OFFER
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.TYPE_OUTGOING_RINGING
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.WEBRTC_NOTIFICATION
import org.thoughtcrime.securesms.webrtc.audio.OutgoingRinger
import org.thoughtcrime.securesms.webrtc.data.Event
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState.CONNECTED
import org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED
import org.webrtc.PeerConnection.IceConnectionState.FAILED
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import org.thoughtcrime.securesms.webrtc.data.State as CallState

//todo PHONE We want to eventually remove this bridging class and move the logic here to a better place, probably in the callManager
/**
 * A class that used to be an Android system in the old codebase and was replaced by a temporary bridging class ro simplify the transition away from
 * system services that handle the call logic. We had to avoid system services in order to circumvent the restrictions around starting a service when
 * the app is in the background or killed.
 * The idea is to eventually remove this class entirely and move its code in a better place (likely directly in the CallManager)
 */
@Singleton
class WebRtcCallBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val callManager: CallManager,
    private val networkConnectivity: NetworkConnectivity,
    private val recipientRepository: RecipientRepository,
    private val storage: StorageProtocol,
    @ManagerScope scope: CoroutineScope,
): CallManager.WebRtcListener, OnAppStartupComponent  {

    companion object {

        private val TAG = Log.tag(WebRtcCallBridge::class.java)

        const val ACTION_IGNORE_CALL = "IGNORE_CALL" // like when swiping off a notification. Ends the call without notifying the caller
        const val ACTION_DENY_CALL = "DENY_CALL"
        const val ACTION_LOCAL_HANGUP = "LOCAL_HANGUP"

        const val EXTRA_RECIPIENT_ADDRESS = "RECIPIENT_ID"
        const val EXTRA_CALL_ID = "call_id"

        private const val TIMEOUT_SECONDS = 60L
        private const val RECONNECT_SECONDS = 5L
        private const val MAX_RECONNECTS = 5

    }

    private var _hasAcceptedCall: MutableStateFlow<Boolean> = MutableStateFlow(false) // always true for outgoing call and true once the user accepts the call for incoming calls
    val hasAcceptedCall: StateFlow<Boolean> = _hasAcceptedCall

    private var currentTimeouts = 0
    private var isNetworkAvailable = true
    private var scheduledTimeout: ScheduledFuture<*>? = null
    private var scheduledReconnect: ScheduledFuture<*>? = null

    private val serviceExecutor = Executors.newSingleThreadExecutor()
    private val timeoutExecutor = Executors.newScheduledThreadPool(1)

    private var wiredHeadsetStateReceiver: WiredHeadsetStateReceiver? = null
    private var powerButtonReceiver: PowerButtonReceiver? = null

    init {
        callManager.registerListener(this)
        _hasAcceptedCall.value = false
        isNetworkAvailable = true
        registerWiredHeadsetStateReceiver()

        scope.launch {
            networkConnectivity.networkAvailable.collectLatest(::networkChange)
        }
    }

    private val Address.isLocalNumber: Boolean
        get() = address.equals(storage.getUserPublicKey(), ignoreCase = true)


    @Synchronized
    private fun terminate() {
        Log.d(TAG, "Terminating rtc service")
        context.stopService(Intent(context, CallForegroundService::class.java))
        NotificationManagerCompat.from(context).cancel(WEBRTC_NOTIFICATION)
        callManager.stop()
        _hasAcceptedCall.value = false
        currentTimeouts = 0
        isNetworkAvailable = true
        scheduledTimeout?.cancel(false)
        scheduledReconnect?.cancel(false)
        scheduledTimeout = null
        scheduledReconnect = null
        callManager.postViewModelState(CallViewModel.State.CALL_INITIALIZING) // reset to default state
        
        //todo PHONE should we refactor ice candidates to be sent prior to answering the call?

    }

    override fun onHangup() {
        serviceExecutor.execute {
            callManager.handleRemoteHangup()

            if (!hasAcceptedCall.value) {
                callManager.recipient?.let { recipient ->
                    insertMissedCall(recipient, true)
                }
            }

            terminate()
        }
    }

    private fun registerWiredHeadsetStateReceiver() {
        wiredHeadsetStateReceiver = WiredHeadsetStateReceiver(::handleWiredHeadsetChanged)
        context.registerReceiver(wiredHeadsetStateReceiver, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))
    }

    private fun handleBusyCall(address: Address) {
        insertMissedCall(address, false)
    }

    private fun handleNewOffer(address: Address, sdp: String, callId: UUID) {
        Log.d(TAG, "Handle new offer")
        callManager.onNewOffer(sdp, callId, address).fail {
            Log.e("Loki", "Error handling new offer", it)
            callManager.postConnectionError()
            terminate()
        }
    }

    fun onIncomingCall(address: Address, sdp: String, callId: UUID, callTime: Long){
        serviceExecutor.execute {
            when {
                // same call / new offer
                callManager.callId == callId &&
                        callManager.currentConnectionState == CallState.Reconnecting -> {
                    handleNewOffer(address, sdp, callId)
                }
                // busy call
                callManager.isBusy(context, callId) -> handleBusyCall(address)
                // in pre offer
                callManager.isPreOffer() -> handleIncomingPreOffer(address, sdp, callId, callTime)
            }
        }
    }

    fun handlePreOffer(address: Address, callId: UUID, callTime: Long) {
        serviceExecutor.execute {
            Log.d(TAG, "Handle pre offer")
            if (!callManager.isIdle()) {
                Log.w(TAG, "Handling pre-offer from non-idle state")
                return@execute
            }

            if (isIncomingMessageExpired(callTime)) {
                Log.d(TAG, "Pre offer expired - message timestamp was deemed expired: ${System.currentTimeMillis() - callTime}s")
                insertMissedCall(address, true)
                terminate()
                return@execute
            }

            callManager.onPreOffer(callId, address) {
                setCallNotification(TYPE_INCOMING_PRE_OFFER, address)
                callManager.postViewModelState(CallViewModel.State.CALL_PRE_OFFER_INCOMING)
                callManager.initializeAudioForCall()
                callManager.startIncomingRinger()
                callManager.setAudioEnabled(true)

                BackgroundPollWorker.scheduleOnce(
                    context,
                    listOf(BackgroundPollWorker.Target.ONE_TO_ONE)
                )
            }
        }
    }

    private fun handleIncomingPreOffer(address: Address, sdp: String, callId: UUID, callTime: Long) {
        serviceExecutor.execute {
            val preOffer = callManager.preOfferCallData
            if (callManager.isPreOffer() && (preOffer == null || preOffer.callId != callId || preOffer.recipient != address)) {
                Log.d(TAG, "Incoming ring from non-matching pre-offer")
                return@execute
            }

            callManager.onIncomingRing(sdp, callId, address, callTime) {
                if (_hasAcceptedCall.value) {
                    setCallNotification(TYPE_INCOMING_CONNECTING, address)
                } else {
                    //No need to do anything here as this case is already taken care of from the pre offer that came before
                }
                callManager.clearPendingIceUpdates()
                callManager.postViewModelState(CallViewModel.State.CALL_OFFER_INCOMING)
                registerPowerButtonReceiver()

                // if the user has already accepted the incoming call, try to answer again
                // (they would have tried to answer when they first accepted
                // but it would have silently failed due to the pre offer having not been set yet
                if (_hasAcceptedCall.value) handleAnswerCall()
            }
        }
    }

    fun handleOutgoingCall(recipient: Address) {
        serviceExecutor.execute {
            if (!callManager.isIdle()) return@execute

            _hasAcceptedCall.value = true // outgoing calls are automatically set to 'accepted'
            callManager.postConnectionEvent(Event.SendPreOffer) {
                callManager.recipient = recipient
                val callId = UUID.randomUUID()
                callManager.callId = callId

                callManager.initializeVideo(context)

                callManager.postViewModelState(CallViewModel.State.CALL_PRE_OFFER_OUTGOING)
                callManager.initializeAudioForCall()
                callManager.startOutgoingRinger(OutgoingRinger.Type.RINGING)
                setCallNotification(TYPE_OUTGOING_RINGING, callManager.recipient)
                callManager.insertCallMessage(
                    recipient.address.toString(),
                    CallMessageType.CALL_OUTGOING
                )
                scheduledTimeout = timeoutExecutor.schedule(
                    TimeoutRunnable(callId, ::handleCheckTimeout),
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
                callManager.setAudioEnabled(true)

                val expectedState = callManager.currentConnectionState
                val expectedCallId = callManager.callId

                try {
                    val offerFuture = callManager.onOutgoingCall(context)
                    offerFuture.fail { e ->
                        if (isConsistentState(
                                expectedState,
                                expectedCallId,
                                callManager.currentConnectionState,
                                callManager.callId
                            )
                        ) {
                            Log.e(TAG, e)
                            callManager.postViewModelState(CallViewModel.State.NETWORK_FAILURE)
                            callManager.postConnectionError()
                            terminate()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, e)
                    callManager.postConnectionError()
                    terminate()
                }
            }
        }
    }

    fun handleAnswerCall() {
        serviceExecutor.execute {
            Log.d(TAG, "Handle answer call")
            _hasAcceptedCall.value = true

            val recipient = callManager.recipient ?: return@execute Log.e(
                TAG,
                "No recipient to answer in handleAnswerCall"
            )
            setCallNotification(TYPE_INCOMING_CONNECTING, recipient)

            if (callManager.pendingOffer == null) {
                return@execute Log.e(TAG, "No pending offer in handleAnswerCall")
            }

            val callId = callManager.callId ?: return@execute Log.e(TAG, "No callId in handleAnswerCall")

            val timestamp = callManager.pendingOfferTime

            if (callManager.currentConnectionState != CallState.RemoteRing) {
                Log.e(TAG, "Can only answer from ringing!")
                return@execute
            }

            if (isIncomingMessageExpired(timestamp)) {
                val didHangup = callManager.postConnectionEvent(Event.TimeOut) {
                    Log.d(TAG, "Answer expired - message timestamp was deemed expired: ${System.currentTimeMillis() - timestamp}s")
                    insertMissedCall(
                        recipient,
                        true
                    ) //todo PHONE do we want a missed call in this case? Or just [xxx] called you ?
                    terminate()
                }
                if (didHangup) {
                    return@execute
                }
            }

            callManager.postConnectionEvent(Event.SendAnswer) {
                callManager.silenceIncomingRinger()

                callManager.postViewModelState(CallViewModel.State.CALL_ANSWER_INCOMING)

                scheduledTimeout = timeoutExecutor.schedule(
                    TimeoutRunnable(callId, ::handleCheckTimeout),
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )

                callManager.initializeAudioForCall()
                callManager.initializeVideo(context)

                val expectedState = callManager.currentConnectionState
                val expectedCallId = callManager.callId

                try {
                    val answerFuture = callManager.onIncomingCall(context)
                    answerFuture.fail { e ->
                        if (isConsistentState(
                                expectedState,
                                expectedCallId,
                                callManager.currentConnectionState,
                                callManager.callId
                            )
                        ) {
                            Log.e(TAG, "incoming call error: $e")
                            insertMissedCall(
                                recipient,
                                true
                            ) //todo PHONE do we want a missed call in this case? Or just [xxx] called you ?
                            callManager.postConnectionError()
                            terminate()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, e)
                    callManager.postConnectionError()
                    terminate()
                }
            }
        }
    }

    fun handleDenyCall() {
        serviceExecutor.execute {
            callManager.handleDenyCall()
            terminate()
        }
    }

    fun handleIgnoreCall(){
        serviceExecutor.execute {
            callManager.handleIgnoreCall()
            terminate()
        }
    }

    fun handleLocalHangup(recipient: Address?) {
        serviceExecutor.execute {
            callManager.handleLocalHangup(recipient)
            terminate()
        }
    }

    fun handleRemoteHangup(callId: UUID) {
        serviceExecutor.execute {
            if (callManager.callId != callId) {
                Log.e(TAG, "Hangup for non-active call...")
                return@execute
            }

            onHangup()
        }
    }

    private fun handleWiredHeadsetChanged(enabled: Boolean) {
        callManager.handleWiredHeadsetChanged(enabled)
    }

    private fun handleScreenOffChange() {
        callManager.handleScreenOffChange()
    }

    fun handleAnswerIncoming(address: Address, sdp: String, callId: UUID) {
        serviceExecutor.execute {
            try {
                if (address.isLocalNumber && callManager.currentConnectionState in CallState.CAN_DECLINE_STATES) {
                    handleLocalHangup(address)
                    return@execute
                }

                callManager.postViewModelState(CallViewModel.State.CALL_ANSWER_OUTGOING)

                callManager.handleResponseMessage(
                    address,
                    callId,
                    SessionDescription(SessionDescription.Type.ANSWER, sdp)
                )
            } catch (e: PeerConnectionException) {
                terminate()
            }
        }
    }

    fun handleRemoteIceCandidate(iceCandidates: List<IceCandidate>, callId: UUID) {
        serviceExecutor.execute {
            Log.d(TAG, "Handle remote ice")
            callManager.handleRemoteIceCandidate(iceCandidates, callId)
        }
    }

    private fun handleIceConnected() {
        serviceExecutor.execute {
            val recipient = callManager.recipient ?: return@execute
            if (callManager.currentCallState == CallViewModel.State.CALL_CONNECTED) return@execute
            Log.d(TAG, "Handle ice connected")

            val connected = callManager.postConnectionEvent(Event.Connect) {
                callManager.postViewModelState(CallViewModel.State.CALL_CONNECTED)
                setCallNotification(TYPE_ESTABLISHED, recipient)
                callManager.startCommunication()
            }
            if (!connected) {
                Log.e("Loki", "Error handling ice connected state transition")
                callManager.postConnectionError()
                terminate()
            }
        }
    }

    private fun registerPowerButtonReceiver() {
        if (powerButtonReceiver == null) {
            powerButtonReceiver = PowerButtonReceiver(::handleScreenOffChange)
            context.registerReceiver(powerButtonReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    private fun handleCheckReconnect(callId: UUID) {
        serviceExecutor.execute {
            val currentCallId = callManager.callId ?: return@execute
            val numTimeouts = ++currentTimeouts

            if (currentCallId == callId && isNetworkAvailable && numTimeouts <= MAX_RECONNECTS) {
                Log.i("Loki", "Trying to re-connect")
                callManager.networkReestablished()
                scheduledTimeout = timeoutExecutor.schedule(
                    TimeoutRunnable(currentCallId, ::handleCheckTimeout),
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            } else if (numTimeouts < MAX_RECONNECTS) {
                Log.i(
                    "Loki",
                    "Network isn't available, timeouts == $numTimeouts out of $MAX_RECONNECTS"
                )
                scheduledReconnect = timeoutExecutor.schedule(
                    CheckReconnectedRunnable(currentCallId, ::handleCheckReconnect),
                    RECONNECT_SECONDS,
                    TimeUnit.SECONDS
                )
            } else {
                Log.i("Loki", "Network isn't available, timing out")
                handleLocalHangup(null)
            }
        }
    }

    private fun handleCheckTimeout(callId: UUID) {
        serviceExecutor.execute {
            val currentCallId = callManager.callId ?: return@execute
            val callState = callManager.currentConnectionState

            if (currentCallId == callId && (callState !in arrayOf(
                    CallState.Connected,
                    CallState.Connecting
                ))
            ) {
                Log.w(TAG, "Timing out call: $callId")
                handleLocalHangup(null)
            }
        }
    }

    /**
     * This method handles displaying notifications relating to the various call states.
     * Those notifications can be shown in two ways:
     * - Directly sent by the notification manager
     * - Displayed as part of a foreground Service
     */
    private fun setCallNotification(type: Int, recipient: Address?) {
        // send appropriate notification if we have permission
        if (
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            when (type) {
                // show a notification directly for this case
                TYPE_INCOMING_PRE_OFFER -> {
                    sendNotification(type, recipient)
                }
                // attempt to show the notification via a service
                else -> {
                    startServiceOrShowNotification(type, recipient)
                }
            }

        } // otherwise if we do not have permission and we have a pre offer, try to open the activity directly (this won't work if the app is backgrounded/killed)
        else if(type == TYPE_INCOMING_PRE_OFFER) {
            // Start an intent for the fullscreen call activity
            val foregroundIntent = WebRtcCallActivity.getCallActivityIntent(context)
                .setAction(WebRtcCallActivity.ACTION_FULL_SCREEN_INTENT)
            context.startActivity(foregroundIntent)
        }

    }

    @SuppressLint("MissingPermission")
    private fun sendNotification(type: Int, recipient: Address?){
        NotificationManagerCompat.from(context).notify(
            WEBRTC_NOTIFICATION,
            CallNotificationBuilder.getCallInProgressNotification(context, type, recipient?.let(recipientRepository::getRecipientSync))
        )
    }

    /**
     * This will attempt to start a service with an attached notification,
     * if the service fails to start a manual notification will be sent
     */
    private fun startServiceOrShowNotification(type: Int, recipient: Address?){
        try {
            ContextCompat.startForegroundService(context, CallForegroundService.startIntent(context, type, recipient))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start Call Service intent: $e")
            sendNotification(type, recipient)
        }
    }

    private fun insertMissedCall(recipient: Address, signal: Boolean) {
        callManager.insertCallMessage(
            threadPublicKey = recipient.address.toString(),
            callMessageType = CallMessageType.CALL_MISSED,
            signal = signal
        )
    }

    private fun isIncomingMessageExpired(timestamp: Long) =
        (System.currentTimeMillis() - timestamp) > TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)

    private fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        callManager.unregisterListener(this)
        wiredHeadsetStateReceiver?.let(context::unregisterReceiver)
        powerButtonReceiver?.let(context::unregisterReceiver)
        callManager.shutDownAudioManager()
        powerButtonReceiver = null
        wiredHeadsetStateReceiver = null
        _hasAcceptedCall.value = false
        currentTimeouts = 0
        isNetworkAvailable = false
    }

    private fun networkChange(networkAvailable: Boolean) {
        Log.d("Loki", "flipping network available to $networkAvailable")
        isNetworkAvailable = networkAvailable
        if (networkAvailable && callManager.currentConnectionState == CallState.Connected) {
            Log.d("Loki", "Should reconnected")
        }
    }

    private class CheckReconnectedRunnable(
        private val callId: UUID, val checkReconnect: (UUID)->Unit
    ) : Runnable {
        override fun run() {
            checkReconnect(callId)
        }
    }

    private class TimeoutRunnable(
        private val callId: UUID, val onCheckTimeout: (UUID)->Unit
    ) : Runnable {
        override fun run() {
            onCheckTimeout(callId)
        }
    }

    private abstract class StateAwareListener<V>(
        private val expectedState: CallState,
        private val expectedCallId: UUID?,
        private val getState: () -> Pair<CallState, UUID?>
    ) : FutureTaskListener<V> {

        companion object {
            private val TAG = Log.tag(StateAwareListener::class.java)
        }

        override fun onSuccess(result: V) {
            if (!isConsistentState()) {
                Log.w(TAG, "State has changed since request, aborting success callback...")
            } else {
                onSuccessContinue(result)
            }
        }

        override fun onFailure(exception: ExecutionException?) {
            if (!isConsistentState()) {
                Log.w(TAG, exception)
                Log.w(TAG, "State has changed since request, aborting failure callback...")
            } else {
                exception?.let {
                    onFailureContinue(it.cause)
                }
            }
        }

        private fun isConsistentState(): Boolean {
            val (currentState, currentCallId) = getState()
            return expectedState == currentState && expectedCallId == currentCallId
        }

        abstract fun onSuccessContinue(result: V)
        abstract fun onFailureContinue(throwable: Throwable?)

    }

    private fun isConsistentState(
        expectedState: CallState,
        expectedCallId: UUID?,
        currentState: CallState,
        currentCallId: UUID?
    ): Boolean {
        return expectedState == currentState && expectedCallId == currentCallId
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        newState?.let { state -> processIceConnectionChange(state) }
    }

    private fun processIceConnectionChange(newState: PeerConnection.IceConnectionState) {
        serviceExecutor.execute {
            if (newState == CONNECTED) {
                scheduledTimeout?.cancel(false)
                scheduledReconnect?.cancel(false)
                scheduledTimeout = null
                scheduledReconnect = null

                handleIceConnected()
            } else if (newState in arrayOf(
                    FAILED,
                    DISCONNECTED
                ) && (scheduledReconnect == null && scheduledTimeout == null)
            ) {
                callManager.callId?.let { callId ->
                    callManager.postConnectionEvent(Event.IceDisconnect) {
                        callManager.postViewModelState(CallViewModel.State.CALL_RECONNECTING)
                        if (callManager.isInitiator()) {
                            Log.i("Loki", "Starting reconnect timer")
                            scheduledReconnect = timeoutExecutor.schedule(
                                CheckReconnectedRunnable(callId, ::handleCheckReconnect),
                                RECONNECT_SECONDS,
                                TimeUnit.SECONDS
                            )
                        } else {
                            Log.i("Loki", "Starting timeout, awaiting new reconnect")
                            callManager.postConnectionEvent(Event.PrepareForNewOffer) {
                                scheduledTimeout = timeoutExecutor.schedule(
                                    TimeoutRunnable(callId, ::handleCheckTimeout),
                                    TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                                )
                            }
                        }
                    }
                } ?: run {
                    handleLocalHangup(null)
                }
            }
            Log.i("Loki", "onIceConnectionChange: $newState")
        }
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {}

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}

    override fun onIceCandidate(p0: IceCandidate?) {}

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}

    override fun onAddStream(p0: MediaStream?) {}

    override fun onRemoveStream(p0: MediaStream?) {}

    override fun onDataChannel(p0: DataChannel?) {}

    override fun onRenegotiationNeeded() {
        Log.w(TAG, "onRenegotiationNeeded was called!")
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
}
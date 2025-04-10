package org.thoughtcrime.securesms.webrtc

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.ViewUtil
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_ANSWER_INCOMING
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_ANSWER_OUTGOING
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_CONNECTED
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_DISCONNECTED
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_HANDLING_ICE
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_OFFER_INCOMING
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_OFFER_OUTGOING
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_PRE_OFFER_INCOMING
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_PRE_OFFER_OUTGOING
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_RECONNECTING
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_SENDING_ICE
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.NETWORK_FAILURE
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.RECIPIENT_UNAVAILABLE
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callManager: CallManager,
    private val rtcCallBridge: WebRtcCallBridge,
    private val usernameUtils: UsernameUtils

): ViewModel() {

    //todo PHONE Can we eventually remove this state and instead use the StateMachine.kt State?
    enum class State {
        CALL_INITIALIZING, // default starting state before any rtc state kicks in

        CALL_PRE_OFFER_INCOMING,
        CALL_PRE_OFFER_OUTGOING,
        CALL_OFFER_INCOMING,
        CALL_OFFER_OUTGOING,
        CALL_ANSWER_INCOMING,
        CALL_ANSWER_OUTGOING,
        CALL_HANDLING_ICE,
        CALL_SENDING_ICE,

        CALL_CONNECTED,
        CALL_DISCONNECTED,
        CALL_RECONNECTING,

        NETWORK_FAILURE,
        RECIPIENT_UNAVAILABLE,
    }

    val floatingRenderer: SurfaceViewRenderer?
        get() = callManager.floatingRenderer

    val fullscreenRenderer: SurfaceViewRenderer?
        get() = callManager.fullscreenRenderer

    val audioDeviceState
        get() = callManager.audioDeviceEvents

    val localAudioEnabledState
        get() = callManager.audioEvents.map { it.isEnabled }

    val videoState: StateFlow<VideoState>
        get() = callManager.videoState

    var deviceOrientation: Orientation = Orientation.UNKNOWN
        set(value) {
            field = value
            callManager.setDeviceOrientation(value)
        }

    val currentCallState get() = callManager.currentCallState

    val initialCallState = CallState("", "", false, false, false)
    val initialAccumulator = CallAccumulator(emptySet(), initialCallState)

    val callState: StateFlow<CallState> = callManager.callStateEvents
        .combine(rtcCallBridge.hasAcceptedCall) { state, accepted ->
            Pair(state, accepted)
        }
        .scan(initialAccumulator) { acc, (state, accepted) ->
            // reset the set on  preoffers
            val newSteps = if (state in listOf(
                    CALL_PRE_OFFER_OUTGOING,
                    CALL_PRE_OFFER_INCOMING
                )
            ) {
                setOf(state)
            } else {
                acc.callSteps + state
            }

            val callTitle = when (state) {
                CALL_PRE_OFFER_OUTGOING, CALL_PRE_OFFER_INCOMING,
                CALL_OFFER_OUTGOING, CALL_OFFER_INCOMING ->
                    context.getString(R.string.callsRinging)
                CALL_ANSWER_INCOMING, CALL_ANSWER_OUTGOING ->
                    context.getString(R.string.callsConnecting)
                CALL_CONNECTED -> ""
                CALL_RECONNECTING -> context.getString(R.string.callsReconnecting)
                RECIPIENT_UNAVAILABLE, CALL_DISCONNECTED ->
                    context.getString(R.string.callsEnded)
                NETWORK_FAILURE -> context.getString(R.string.callsErrorStart)
                else -> acc.callState.callLabelTitle // keep previous title
            }

            val callSubtitle = when (state) {
                CALL_PRE_OFFER_OUTGOING -> constructCallLabel(R.string.creatingCall, newSteps.size)
                CALL_PRE_OFFER_INCOMING -> constructCallLabel(R.string.receivingPreOffer, newSteps.size)
                CALL_OFFER_OUTGOING -> constructCallLabel(R.string.sendingCallOffer, newSteps.size)
                CALL_OFFER_INCOMING -> constructCallLabel(R.string.receivingCallOffer, newSteps.size)
                CALL_ANSWER_OUTGOING, CALL_ANSWER_INCOMING -> constructCallLabel(R.string.receivedAnswer, newSteps.size)
                CALL_SENDING_ICE -> constructCallLabel(R.string.sendingConnectionCandidates, newSteps.size)
                CALL_HANDLING_ICE -> constructCallLabel(R.string.handlingConnectionCandidates, newSteps.size)
                else -> ""
            }

            val showCallControls = state in listOf(
                CALL_CONNECTED,
                CALL_PRE_OFFER_OUTGOING,
                CALL_OFFER_OUTGOING,
                CALL_ANSWER_OUTGOING,
                CALL_ANSWER_INCOMING
            ) || (state in listOf(
                CALL_PRE_OFFER_INCOMING,
                CALL_OFFER_INCOMING,
                CALL_HANDLING_ICE,
                CALL_SENDING_ICE
            ) && accepted)

            val showEndCallButton = showCallControls || state == CALL_RECONNECTING

            val showPreCallButtons = state in listOf(
                CALL_PRE_OFFER_INCOMING,
                CALL_OFFER_INCOMING,
                CALL_HANDLING_ICE,
                CALL_SENDING_ICE
            ) && !accepted

            val newCallState = CallState(
                callLabelTitle = callTitle,
                callLabelSubtitle = callSubtitle,
                showCallButtons = showCallControls,
                showPreCallButtons = showPreCallButtons,
                showEndCallButton = showEndCallButton
            )

            CallAccumulator(newSteps, newCallState)
        }
        .map { it.callState }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), initialCallState)


    val recipient get() = callManager.recipientEvents
    val callStartTime: Long get() = callManager.callStartTime

    data class CallAccumulator(
        val callSteps: Set<CallViewModel.State>,
        val callState: CallState
    )

    private val MAX_CALL_STEPS: Int = 5

    private fun constructCallLabel(@StringRes label: Int, stepsCount: Int): String {
        return ViewUtil.safeRTLString(context, "${context.getString(label)} $stepsCount/$MAX_CALL_STEPS")
    }


    fun swapVideos() = callManager.swapVideos()

    fun toggleMute() = callManager.toggleMuteAudio()

    fun toggleSpeakerphone() = callManager.toggleSpeakerphone()

    fun toggleVideo() = callManager.toggleVideo()

    fun flipCamera() = callManager.flipCamera()

    fun answerCall() = rtcCallBridge.handleAnswerCall()

    fun denyCall() = rtcCallBridge.handleDenyCall()

    fun createCall(recipientAddress: Address) =
        rtcCallBridge.handleOutgoingCall(Recipient.from(context, recipientAddress, true))

    fun hangUp() = rtcCallBridge.handleLocalHangup(null)

    fun getContactName(accountID: String) = usernameUtils.getContactNameWithAccountID(accountID)

    fun getCurrentUsername() = usernameUtils.getCurrentUsernameWithAccountIdFallback()

    data class CallState(
        val callLabelTitle: String?,
        val callLabelSubtitle: String,
        val showCallButtons: Boolean,
        val showPreCallButtons: Boolean,
        val showEndCallButton: Boolean
    )
}
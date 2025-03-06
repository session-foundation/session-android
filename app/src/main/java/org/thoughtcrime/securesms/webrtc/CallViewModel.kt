package org.thoughtcrime.securesms.webrtc

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.UsernameUtils
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
import kotlin.math.min

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
    val callState: StateFlow<CallState> = callManager.callStateEvents
        .combine(rtcCallBridge.hasAcceptedCall){ state, accepted ->

            // reset the set on  preoffers
            if(state in listOf(CALL_PRE_OFFER_OUTGOING, CALL_PRE_OFFER_INCOMING)) {
                callSteps.clear()
            }
            callSteps.add(state)

            val callTitle = when (state) {
                CALL_PRE_OFFER_OUTGOING, CALL_PRE_OFFER_INCOMING,
                CALL_OFFER_OUTGOING, CALL_OFFER_INCOMING,
                    -> context.getString(R.string.callsRinging)

                CALL_ANSWER_INCOMING,
                CALL_ANSWER_OUTGOING,
                    -> context.getString(R.string.callsConnecting)

                CALL_CONNECTED -> ""

                CALL_RECONNECTING -> context.getString(R.string.callsReconnecting)
                RECIPIENT_UNAVAILABLE,
                CALL_DISCONNECTED -> context.getString(R.string.callsEnded)

                NETWORK_FAILURE -> context.getString(R.string.callsErrorStart)

                else -> null // null means the view can keep its existing text
            }

            val callSubtitle = when (state) {
                CALL_PRE_OFFER_OUTGOING -> constructCallLabel(R.string.creatingCall)
                CALL_PRE_OFFER_INCOMING -> constructCallLabel(R.string.receivingPreOffer)

                CALL_OFFER_OUTGOING -> constructCallLabel(R.string.sendingCallOffer)
                CALL_OFFER_INCOMING -> constructCallLabel(R.string.receivingCallOffer)

                CALL_ANSWER_OUTGOING, CALL_ANSWER_INCOMING -> constructCallLabel(R.string.receivedAnswer)

                CALL_SENDING_ICE -> constructCallLabel(R.string.sendingConnectionCandidates)
                CALL_HANDLING_ICE -> constructCallLabel(R.string.handlingConnectionCandidates)

                else -> ""
            }

            // buttons visibility
            val showCallControls = state in listOf(
                CALL_CONNECTED,
                CALL_PRE_OFFER_OUTGOING,
                CALL_OFFER_OUTGOING,
                CALL_ANSWER_OUTGOING,
                CALL_ANSWER_INCOMING,
            ) || (state in listOf(
                CALL_PRE_OFFER_INCOMING,
                CALL_OFFER_INCOMING,
                CALL_HANDLING_ICE,
                CALL_SENDING_ICE
            ) && accepted)


            val showEndCallButton = showCallControls || state == CALL_RECONNECTING

            val showPreCallButtons =
                state in listOf(
                    CALL_PRE_OFFER_INCOMING,
                    CALL_OFFER_INCOMING,
                    CALL_HANDLING_ICE,
                    CALL_SENDING_ICE
                ) && !accepted

            CallState(
                callLabelTitle = callTitle,
                callLabelSubtitle = callSubtitle,
                showCallButtons = showCallControls,
                showPreCallButtons = showPreCallButtons,
                showEndCallButton = showEndCallButton
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), CallState("", "", false, false, false))

    val recipient get() = callManager.recipientEvents
    val callStartTime: Long get() = callManager.callStartTime

    private var callSteps: MutableSet<State> = mutableSetOf()
    private val MAX_CALL_STEPS: Int = 5

    private fun constructCallLabel(@StringRes label: Int): String {
        return if(ViewUtil.isLtr(context)) "${context.getString(label)} ${callSteps.size}/$MAX_CALL_STEPS" else "$MAX_CALL_STEPS/${callSteps.size} ${context.getString(label)}"
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
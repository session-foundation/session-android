package org.thoughtcrime.securesms.webrtc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.res.ColorStateList
import android.graphics.Outline
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityWebrtcBinding
import org.apache.commons.lang3.time.DurationFormatUtils
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.CALL_CONNECTED
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager.AudioDevice.SPEAKER_PHONE
import org.thoughtcrime.securesms.webrtc.data.State
import java.time.Duration

@AndroidEntryPoint
class WebRtcCallActivity : ScreenLockActionBarActivity() {

    companion object {
        private val TAG = Log.tag(WebRtcCallActivity::class.java)
        const val ACTION_FULL_SCREEN_INTENT = "fullscreen-intent"
        const val ACTION_ANSWER = "answer"
        const val ACTION_START_CALL = "start-call"

        const val EXTRA_RECIPIENT_ADDRESS = "RECIPIENT_ID"

        fun getCallActivityIntent(context: Context): Intent{
            return Intent(context, WebRtcCallActivity::class.java)
                .setFlags(FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
    }

    private val viewModel by viewModels<CallViewModel>()
    private lateinit var binding: ActivityWebrtcBinding
    private var uiJob: Job? = null

    private val CALL_DURATION_FORMAT_HOURS = "HH:mm:ss"
    private val CALL_DURATION_FORMAT_MINS = "mm:ss"
    private val ONE_HOUR: Long = Duration.ofHours(1).toMillis()

    private val buttonColorEnabled by lazy { getColor(R.color.white) }
    private val buttonColorDisabled by lazy { getColorFromAttr(R.attr.disabled) }

    /**
     * We need to track the device's orientation so we can calculate whether or not to rotate the video streams
     * This works a lot better than using `OrientationEventListener > onOrientationChanged'
     * which gives us a rotation angle that doesn't take into account pitch vs roll, so tipping the device from front to back would
     * trigger the video rotation logic, while we really only want it when the device is in portrait or landscape.
     */
    private var orientationManager = OrientationManager(this)

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)

        binding = ActivityWebrtcBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        binding.floatingRendererContainer.setOnClickListener {
            viewModel.swapVideos()
        }

        binding.microphoneButton.setOnClickListener {
            viewModel.toggleMute()
        }

        binding.speakerPhoneButton.setOnClickListener {
            viewModel.toggleSpeakerphone()
        }

        binding.acceptCallButton.setOnClickListener {
            answerCall()
        }

        binding.declineCallButton.setOnClickListener {
            denyCall()
        }

        binding.enableCameraButton.setOnClickListener {
            Permissions.with(this)
                .request(Manifest.permission.CAMERA)
                .onAllGranted {
                    viewModel.toggleVideo()
                }
                .execute()
        }

        binding.switchCameraButton.setOnClickListener {
            viewModel.flipCamera()
        }

        binding.endCallButton.setOnClickListener {
            hangUp()
        }
        binding.backArrow.setOnClickListener {
            onBackPressed()
        }

        lifecycleScope.launch {
            orientationManager.orientation.collect { orientation ->
                viewModel.deviceOrientation = orientation
                updateControlsRotation()
            }
        }

        clipFloatingInsets()

        // set up the user avatar
        binding.userAvatar.setThemedContent {
            Avatar(
                size = LocalDimensions.current.iconXLargeAvatar,
                data = viewModel.currentUserAvatarData.collectAsState().value
            )
        }

        // set up recipient's avatar
        binding.contactAvatar.setThemedContent {
            Avatar(
                size = LocalDimensions.current.iconXLargeAvatar,
                data = viewModel.recipientAvatarData.collectAsState().value
            )
        }

        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        Log.d(TAG, "Web RTC activity handle intent ${intent.action}")
        if (intent.action == ACTION_START_CALL && intent.hasExtra(EXTRA_RECIPIENT_ADDRESS)) {
            viewModel.createCall(IntentCompat.getParcelableExtra(intent, EXTRA_RECIPIENT_ADDRESS, Address::class.java)!!)
        }
        if (intent.action == ACTION_ANSWER) {
            answerCall()
        }

        if (intent.action == ACTION_FULL_SCREEN_INTENT) {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
        }
    }

    /**
     * Makes sure the floating video inset has clipped rounded corners, included with the video stream itself
     */
    private fun clipFloatingInsets() {
        // clip the video inset with rounded corners
        val videoInsetProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // all corners
                outline.setRoundRect(
                    0, 0, view.width, view.height,
                    resources.getDimensionPixelSize(R.dimen.video_inset_radius).toFloat()
                )
            }
        }

        binding.floatingRendererContainer.outlineProvider = videoInsetProvider
        binding.floatingRendererContainer.clipToOutline = true
    }

    override fun onResume() {
        super.onResume()
        orientationManager.startOrientationListener()

    }

    override fun onPause() {
        super.onPause()
        orientationManager.stopOrientationListener()
    }

    override fun onDestroy() {
        super.onDestroy()

        orientationManager.destroy()
    }

    private fun answerCall() {
        viewModel.answerCall()
    }

    private fun denyCall(){
        viewModel.denyCall()
    }

    private fun hangUp(){
        viewModel.hangUp()
    }

    private fun updateControlsRotation() {
        with (binding) {
            val rotation = when(viewModel.deviceOrientation){
                Orientation.LANDSCAPE -> -90f
                Orientation.REVERSED_LANDSCAPE -> 90f
                else -> 0f
            }

            userAvatar.animate().cancel()
            userAvatar.animate().rotation(rotation).start()
            contactAvatar.animate().cancel()
            contactAvatar.animate().rotation(rotation).start()

            speakerPhoneButton.animate().cancel()
            speakerPhoneButton.animate().rotation(rotation).start()

            microphoneButton.animate().cancel()
            microphoneButton.animate().rotation(rotation).start()

            enableCameraButton.animate().cancel()
            enableCameraButton.animate().rotation(rotation).start()

            switchCameraButton.animate().cancel()
            switchCameraButton.animate().rotation(rotation).start()

            endCallButton.animate().cancel()
            endCallButton.animate().rotation(rotation).start()
        }
    }

    private fun updateControls(callState: CallViewModel.CallState) {
        with(binding) {
            // set up title and subtitle
            callTitle.text = callState.callLabelTitle ?: callTitle.text // keep existing text if null

            callSubtitle.text = callState.callLabelSubtitle
            callSubtitle.isVisible = callSubtitle.text.isNotEmpty()

            // buttons visibility
            controlGroup.isVisible = callState.showCallButtons
            endCallButton.isVisible = callState.showEndCallButton
            incomingControlGroup.isVisible = callState.showPreCallButtons
        }
    }

    override fun onStart() {
        super.onStart()

        uiJob = lifecycleScope.launch {

            launch {
                viewModel.audioDeviceState.collect { state ->
                    val speakerEnabled = state.selectedDevice == SPEAKER_PHONE
                    // change drawable background to enabled or not
                    binding.speakerPhoneButton.isSelected = speakerEnabled
                }
            }

            launch {
                viewModel.callState.collect { data ->
                    updateControls(data)
                }
            }


            launch {
                viewModel.connectionState
                    .collect { s ->
                        if (s == State.Disconnected) {
                            Log.d(TAG, "Received a Disconnected State in webrtc activity - finishing.")
                            finish()
                        }
                    }
            }

            // Observing recipient's name
            launch {
                viewModel.recipient
                    .map { it?.displayName(attachesBlindedId = false).orEmpty() }
                    .distinctUntilChanged()
                    .collectLatest { name ->
                        supportActionBar?.title = name
                        binding.remoteRecipientName.text = name
                    }
            }


            launch {
                while (isActive) {
                    val startTime = viewModel.callStartTime
                    if (startTime != -1L) {
                        if(viewModel.currentCallState == CALL_CONNECTED) {
                            val duration = System.currentTimeMillis() - startTime
                            // apply format based on whether the call is more than 1h long
                            val durationFormat = if (duration > ONE_HOUR) CALL_DURATION_FORMAT_HOURS else CALL_DURATION_FORMAT_MINS
                            binding.callTitle.text = DurationFormatUtils.formatDuration(
                                duration,
                                durationFormat
                            )
                        }
                    }

                    delay(1_000)
                }
            }

            launch {
                viewModel.localAudioEnabledState.collect { isEnabled ->
                    // change drawable background to enabled or not
                    binding.microphoneButton.isSelected = !isEnabled
                }
            }

            // handle video state
            launch {
                viewModel.videoState.collect { state ->
                    binding.floatingRenderer.removeAllViews()
                    binding.fullscreenRenderer.removeAllViews()

                    // handle fullscreen video window
                    if(state.showFullscreenVideo()){
                        viewModel.fullscreenRenderer?.let { surfaceView ->
                            binding.fullscreenRenderer.addView(surfaceView)
                            binding.fullscreenRenderer.isVisible = true
                            hideAvatar()
                        }
                    } else {
                        binding.fullscreenRenderer.isVisible = false
                        showAvatar(state.swapped)
                    }

                    // handle floating video window
                    if(state.showFloatingVideo()){
                        viewModel.floatingRenderer?.let { surfaceView ->
                            binding.floatingRenderer.addView(surfaceView)
                            binding.floatingRenderer.isVisible = true
                            binding.swapViewIcon.bringToFront()
                        }
                    } else {
                        binding.floatingRenderer.isVisible = false
                    }

                    // the floating video inset (empty or not) should be shown
                    // the moment we have either of the video streams
                    val showFloatingContainer = state.userVideoEnabled || state.remoteVideoEnabled
                    binding.floatingRendererContainer.isVisible = showFloatingContainer
                    binding.swapViewIcon.isVisible = showFloatingContainer

                    // make sure to default to the contact's avatar if the floating container is not visible
                    if (!showFloatingContainer) showAvatar(false)

                    // handle buttons
                    binding.enableCameraButton.isSelected = state.userVideoEnabled
                    binding.switchCameraButton.isEnabled = state.userVideoEnabled
                    binding.switchCameraButton.imageTintList =
                        ColorStateList.valueOf(
                            if(state.userVideoEnabled) buttonColorEnabled
                            else buttonColorDisabled
                        )
                }
            }
        }
    }

    /**
     * Shows the avatar image.
     * If @showUserAvatar is true, the user's avatar is shown, otherwise the contact's avatar is shown.
     */
    private fun showAvatar(showUserAvatar: Boolean) {
        binding.userAvatar.isVisible = showUserAvatar
        binding.contactAvatar.isVisible = !showUserAvatar
    }

    private fun hideAvatar() {
        binding.userAvatar.isVisible = false
        binding.contactAvatar.isVisible = false
    }

    override fun onStop() {
        super.onStop()
        uiJob?.cancel()
        binding.fullscreenRenderer.removeAllViews()
        binding.floatingRenderer.removeAllViews()
    }
}
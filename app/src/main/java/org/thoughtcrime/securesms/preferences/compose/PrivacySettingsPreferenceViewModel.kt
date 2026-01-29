package org.thoughtcrime.securesms.preferences.compose

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.DISABLE_PASSPHRASE_PREF
import org.session.libsession.utilities.observeBooleanKey
import org.session.libsession.utilities.withMutableUserConfigs
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.areNotificationsEnabled
import javax.inject.Inject

@HiltViewModel
class PrivacySettingsPreferenceViewModel @Inject constructor(
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactory,
    private val app: Application,
    private val typingStatusRepository: TypingStatusRepository,
) : ViewModel() {

    private val keyguardSecure = MutableStateFlow(true)

    private val mutableEvents = MutableSharedFlow<PrivacySettingsPreferenceEvent>()
    val events get() = mutableEvents

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState

    init {
        combine(
            prefs.observeBooleanKey(TextSecurePreferences.SCREEN_LOCK, default = false),
            prefs.observeBooleanKey(DISABLE_PASSPHRASE_PREF, default = false),
            keyguardSecure
        ) { screenLock, isPasswordDisabled, keyguardSecure ->

            val isScreenLockEnabled = isPasswordDisabled && !keyguardSecure
            UIState(
                screenLockVisible = !isPasswordDisabled,
                screenLockEnabled = isScreenLockEnabled,
                screenLockChecked = if (isScreenLockEnabled) false else screenLock
            )
            val passwordDisabled = TextSecurePreferences.isPasswordDisabled(app)

        }.onEach { it ->
            _uiState.update {
                it.copy(
                    screenLockVisible = it.screenLockVisible,
                    screenLockEnabled = it.screenLockEnabled,
                    screenLockChecked = it.screenLockChecked
                )
            }
        }.launchIn(viewModelScope)

        combine(
            prefs.observeBooleanKey(TextSecurePreferences.CALL_NOTIFICATIONS_ENABLED, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.READ_RECEIPTS_PREF, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.TYPING_INDICATORS, default = true),
            prefs.observeBooleanKey(TextSecurePreferences.LINK_PREVIEWS, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.INCOGNITO_KEYBOARD_PREF, default = false)
        ) { callsEnabled, isReadReceiptsEnabled, showTypingIndicator, linkPreviewEnabled, isIncognitoKeyboard
            ->
            UIState(
                typingIndicators = showTypingIndicator,
                callNotificationsEnabled = callsEnabled,

                readReceiptsEnabled = isReadReceiptsEnabled,
                linkPreviewEnabled = linkPreviewEnabled,
                incognitoKeyboardEnabled = isIncognitoKeyboard,
                allowCommunityMessageRequests = _uiState.value.allowCommunityMessageRequests,

                showCallsWarningDialog = _uiState.value.showCallsWarningDialog,
                showCallsNotificationDialog = _uiState.value.showCallsNotificationDialog
            )
        }.onEach { it ->
            _uiState.value = it
        }.launchIn(viewModelScope)
    }

    fun refreshKeyguardSecure() {
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardSecure.value = km.isKeyguardSecure
    }

    fun onToggleScreenLock(enabled: Boolean) {
        // if UI disabled it, ignore
        if (!uiState.value.screenLockEnabled) return
        prefs.setScreenLockEnabled(enabled)
    }

    fun onToggleTypingIndicators() {
        val toggledEnable = !uiState.value.typingIndicators
        prefs.setTypingIndicatorsEnabled(toggledEnable)
        if (!toggledEnable) typingStatusRepository.clear()
    }

    fun onToggleCallNotifications(isEnabled : Boolean) {
        prefs.setCallNotificationsEnabled(isEnabled)
        if(isEnabled && !areNotificationsEnabled(app)){
            _uiState.update { it.copy(showCallsNotificationDialog = true) }
        }
    }

    fun onSetAllowMessageRequests() {
        val allowRequest = !readAllowMessageRequests()
        configFactory.withMutableUserConfigs {
            it.userProfile.setCommunityMessageRequests(allowRequest)
        }
        // update state
        _uiState.update { it.copy(allowCommunityMessageRequests = allowRequest) }
    }

    private fun readAllowMessageRequests(): Boolean =
        configFactory.withMutableUserConfigs { it.userProfile.getCommunityMessageRequests() }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ToggleCallsNotification -> {
                onToggleCallNotifications(command.isEnabled)
            }

            Commands.ToggleLockApp -> {
                onToggleScreenLock(!uiState.value.screenLockChecked)
            }

            is Commands.ToggleCommunityRequests -> {
                onSetAllowMessageRequests()
            }

            Commands.ToggleReadReceipts -> {
                prefs.setReadReceiptsEnabled(!uiState.value.readReceiptsEnabled)
            }

            Commands.ToggleTypingIndicators -> {
                onToggleTypingIndicators()
            }

            Commands.ToggleLinkPreviews -> {
                prefs.setLinkPreviewsEnabled(!uiState.value.linkPreviewEnabled)
            }

            Commands.ToggleIncognitoKeyboard -> {
                prefs.setIncognitoKeyboardEnabled(!uiState.value.incognitoKeyboardEnabled)
            }

            Commands.AskMicPermission -> {
                // Ask for permission
                viewModelScope.launch {
                    mutableEvents.emit(PrivacySettingsPreferenceEvent.AskMicrophonePermission)
                }
            }

            Commands.NavigateToAppNotificationsSettings -> {
                viewModelScope.launch {
                    mutableEvents.emit(PrivacySettingsPreferenceEvent.OpenAppNotificationSettings)
                }
            }

            Commands.HideCallsWarningDialog -> {
                _uiState.update {
                    it.copy(
                        showCallsWarningDialog = false
                    )
                }
            }

            Commands.ShowCallsWarningDialog -> {
                _uiState.update {
                    it.copy(
                        showCallsWarningDialog = true
                    )
                }
            }

            Commands.HideCallsNotificationDialog -> {
                _uiState.update {
                    it.copy(
                        showCallsNotificationDialog = false
                    )
                }
            }

            Commands.ShowCallsNotificationDialog -> {
                _uiState.update {
                    it.copy(
                        showCallsNotificationDialog = true
                    )
                }
            }
        }
    }

    sealed interface Commands {
        data class ToggleCallsNotification(val isEnabled : Boolean) : Commands
        data object ToggleLockApp : Commands // prefs?
        data object ToggleCommunityRequests : Commands // config
        data object ToggleReadReceipts : Commands // prefs
        data object ToggleTypingIndicators : Commands // prefs
        data object ToggleLinkPreviews : Commands // prefs
        data object ToggleIncognitoKeyboard : Commands // prefs

        data object AskMicPermission : Commands
        data object NavigateToAppNotificationsSettings : Commands

        // Dialog for Calls warning
        data object HideCallsWarningDialog : Commands
        data object ShowCallsWarningDialog : Commands

        // show a dialog saying that calls won't work properly if you don't have notifications on at a system level
        data object HideCallsNotificationDialog : Commands
        data object ShowCallsNotificationDialog : Commands
    }

    sealed interface PrivacySettingsPreferenceEvent {
        data object StartLockToggledService : PrivacySettingsPreferenceEvent
        data object OpenAppNotificationSettings : PrivacySettingsPreferenceEvent
        data object AskMicrophonePermission : PrivacySettingsPreferenceEvent
    }

    data class UIState(
        val screenLockVisible: Boolean = false,
        val screenLockEnabled: Boolean = false,
        val screenLockChecked: Boolean = false,

        val readReceiptsEnabled: Boolean = false,
        val typingIndicators: Boolean = false,
        val callNotificationsEnabled: Boolean = false,
        val incognitoKeyboardEnabled: Boolean = false,
        val linkPreviewEnabled: Boolean = false,

        val allowCommunityMessageRequests: Boolean = false, // Get from userConfigs

        val showCallsWarningDialog: Boolean = false,
        val showCallsNotificationDialog: Boolean = false
    )
}
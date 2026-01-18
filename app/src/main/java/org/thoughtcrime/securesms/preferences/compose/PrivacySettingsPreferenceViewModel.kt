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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.observeBooleanKey
import org.session.libsession.utilities.withMutableUserConfigs
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import javax.inject.Inject

@HiltViewModel
class PrivacySettingsPreferenceViewModel @Inject constructor(
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactory,
    private val app: Application,
    private val typingStatusRepository: TypingStatusRepository,
) : ViewModel() {

    private val keyguardSecure = MutableStateFlow(true)

    private val mutableEvents =
        MutableSharedFlow<PrivacySettingsPreferenceEvent>(extraBufferCapacity = 16)
    val events = mutableEvents.asSharedFlow()

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState

    init {
        combine(
            prefs.observeBooleanKey(TextSecurePreferences.SCREEN_LOCK, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.TYPING_INDICATORS, default = true),
            prefs.observeBooleanKey(
                TextSecurePreferences.CALL_NOTIFICATIONS_ENABLED,
                default = false
            ),
            keyguardSecure
        ) { screenLock, typing, callsEnabled, keyguardSecure ->

            val passwordDisabled = TextSecurePreferences.isPasswordDisabled(app)

            val showScreenLock = passwordDisabled
            val screenLockEnabled = passwordDisabled && keyguardSecure // if not secure, disable

            UIState(
                screenLockVisible = showScreenLock,
                screenLockEnabled = screenLockEnabled,
                screenLockChecked = if (screenLockEnabled) screenLock else false,

                typingIndicators = typing,
                callNotificationsEnabled = callsEnabled,

                readReceiptsEnabled = _uiState.value.readReceiptsEnabled,
                allowCommunityMessageRequests = _uiState.value.allowCommunityMessageRequests,
                showCallsWarningDialog = _uiState.value.showCallsWarningDialog
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

    fun onToggleTypingIndicators(enabled: Boolean) {
        prefs.setTypingIndicatorsEnabled(enabled)
        if (!enabled) typingStatusRepository.clear()
    }

    fun onToggleCallNotifications(enabled: Boolean) {
        prefs.setBooleanPreference(TextSecurePreferences.CALL_NOTIFICATIONS_ENABLED, enabled)
        if (enabled) {
            _uiState.update { it.copy(showCallsWarningDialog = true) }
        }
    }

    fun onSetAllowMessageRequests(enabled: Boolean) {
        configFactory.withMutableUserConfigs {
            it.userProfile.setCommunityMessageRequests(enabled)
        }
        // update state
        _uiState.update { it.copy(allowCommunityMessageRequests = enabled) }
    }

    private fun readAllowMessageRequests(): Boolean =
        configFactory.withMutableUserConfigs { it.userProfile.getCommunityMessageRequests() }

    sealed interface Commands {
        data object ToggleCallsNotification : Commands
        data object ToggleLockApp : Commands
        data object ToggleCommunityRequests : Commands
        data object ToggleReadReceipts : Commands
        data object ToggleTypingIndicators : Commands
        data object ToggleLinkPreviews : Commands
        data object ToggleIncognitoKeyboard : Commands
    }

    sealed interface PrivacySettingsPreferenceEvent {
        data object StartLockToggledService : PrivacySettingsPreferenceEvent
        data object OpenAppNotificationSettings : PrivacySettingsPreferenceEvent
    }

    data class UIState(
        val screenLockVisible: Boolean = false,
        val screenLockEnabled: Boolean = false,
        val screenLockChecked: Boolean = false,
        val readReceiptsEnabled: Boolean = false,
        val typingIndicators: Boolean = false,
        val callNotificationsEnabled: Boolean = false,
        val allowCommunityMessageRequests: Boolean = false,

        val showCallsWarningDialog: Boolean = false,
    )
}
package org.thoughtcrime.securesms.preferences.compose

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    private val allowMessageRequests = MutableStateFlow(readAllowMessageRequests())

    private val mutableEvents = MutableSharedFlow<Effect>(extraBufferCapacity = 16)
    val events = mutableEvents.asSharedFlow()

    val uiState: StateFlow<UiState> =
        combine(
            prefs.observeBooleanKey(TextSecurePreferences.SCREEN_LOCK, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.TYPING_INDICATORS, default = true),
            prefs.observeBooleanKey(
                TextSecurePreferences.CALL_NOTIFICATIONS_ENABLED,
                default = false
            ),
            allowMessageRequests,
            keyguardSecure
        ) { screenLock, typing, callsEnabled, allowReq, keyguardSecure ->

            val passwordDisabled = TextSecurePreferences.isPasswordDisabled(app)

            val showScreenLock = passwordDisabled
            val screenLockEnabled = passwordDisabled && keyguardSecure // if not secure, disable

            UiState(
                screenLockVisible = showScreenLock,
                screenLockEnabled = screenLockEnabled,
                screenLockChecked = if (screenLockEnabled) screenLock else false,

                typingIndicators = typing,
                callNotificationsEnabled = callsEnabled,

                allowMessageRequests = allowReq,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    fun refreshKeyguardSecure() {
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardSecure.value = km.isKeyguardSecure
    }

    fun onToggleScreenLock(enabled: Boolean) {
        // if UI disabled it, ignore
        if (!uiState.value.screenLockEnabled) return

        prefs.setScreenLockEnabled(enabled)
        mutableEvents.tryEmit(Effect.StartLockToggledService)
    }

    fun onToggleTypingIndicators(enabled: Boolean) {
        prefs.setTypingIndicatorsEnabled(enabled)
        if (!enabled) typingStatusRepository.clear()
    }

    fun onToggleCallNotifications(enabled: Boolean) {
        prefs.setBooleanPreference(TextSecurePreferences.CALL_NOTIFICATIONS_ENABLED, enabled)
        if (enabled) {
            mutableEvents.tryEmit(Effect.MaybeShowCallsNeedNotificationsWarning)
        }
    }

    fun onSetAllowMessageRequests(enabled: Boolean) {
        configFactory.withMutableUserConfigs {
            it.userProfile.setCommunityMessageRequests(enabled)
        }
        // update state
        allowMessageRequests.value = enabled
    }

    private fun readAllowMessageRequests(): Boolean =
        configFactory.withMutableUserConfigs { it.userProfile.getCommunityMessageRequests() }

    sealed interface Effect {
        data object StartLockToggledService : Effect
        data object MaybeShowCallsNeedNotificationsWarning : Effect
        data object OpenAppNotificationSettings : Effect
    }

    data class UiState(
        val screenLockVisible: Boolean = true,
        val screenLockEnabled: Boolean = true,
        val screenLockChecked: Boolean = false,
        val typingIndicators: Boolean = true,
        val callNotificationsEnabled: Boolean = false,
        val allowMessageRequests: Boolean = true,

        val showCallsWarningDialog: Boolean = false,
    )
}
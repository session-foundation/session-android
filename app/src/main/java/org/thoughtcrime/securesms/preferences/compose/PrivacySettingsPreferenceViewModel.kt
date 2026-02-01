package org.thoughtcrime.securesms.preferences.compose

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.CALL_NOTIFICATIONS_ENABLED
import org.session.libsession.utilities.TextSecurePreferences.Companion.DISABLE_PASSPHRASE_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.INCOGNITO_KEYBOARD_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.READ_RECEIPTS_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.SCREEN_LOCK
import org.session.libsession.utilities.TextSecurePreferences.Companion.TYPING_INDICATORS
import org.session.libsession.utilities.TextSecurePreferences.Companion.LINK_PREVIEWS
import org.session.libsession.utilities.observeBooleanKey
import org.session.libsession.utilities.withMutableUserConfigs
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.preferences.compose.PrivacySettingsPreferenceViewModel.Commands.ShowCallsWarningDialog
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

    private val mutableEvents = MutableSharedFlow<PrivacySettingsPreferenceEvent>(
        replay = 1,
        extraBufferCapacity = 1
    )
    val events get() = mutableEvents

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState

    private val isCommunityMessageRequestsEnabled: Boolean
        get() = configFactory.withMutableUserConfigs { it.userProfile.getCommunityMessageRequests() }

    private val _scrollAction = MutableStateFlow(ScrollAction())
    val scrollAction: StateFlow<ScrollAction> = _scrollAction

    // Use this to get index for UI item. We need the index to tell the listState where to scroll
    // list things ordered by how they appear on the list
    private var prefItemsOrder = listOf(
        CALL_NOTIFICATIONS_ENABLED,
        SCREEN_LOCK,
        "community_message_requests",
        READ_RECEIPTS_PREF,
        TYPING_INDICATORS,
        LINK_PREVIEWS,
        INCOGNITO_KEYBOARD_PREF
    )

    private val screenLockFlow =
        combine(
            prefs.observeBooleanKey(SCREEN_LOCK, default = false),
            prefs.observeBooleanKey(DISABLE_PASSPHRASE_PREF, default = false),
            keyguardSecure
        ) { screenLockPref, isPasswordDisabled, keyguard ->

            val visible = isPasswordDisabled
            val enabled = isPasswordDisabled && keyguard
            val checked = if (enabled) screenLockPref else false

            ScreenLockPrefsData(visible, enabled, checked)
        }

    private val togglesFlow =
        combine(
            prefs.observeBooleanKey(CALL_NOTIFICATIONS_ENABLED, default = false),
            prefs.observeBooleanKey(READ_RECEIPTS_PREF, default = false),
            prefs.observeBooleanKey(TYPING_INDICATORS, default = false),
            prefs.observeBooleanKey(LINK_PREVIEWS, default = false),
            prefs.observeBooleanKey(INCOGNITO_KEYBOARD_PREF, default = false),
        ) { callsEnabled, readReceipts, typing, linkPreviews, incognito ->
            TogglePrefsData(callsEnabled, readReceipts, typing, linkPreviews, incognito)
        }

    private val prefsUiState =
        combine(screenLockFlow, togglesFlow) { lock, toggles ->
            UIState(
                screenLockVisible = lock.visible,
                screenLockEnabled = lock.enabled,
                screenLockChecked = lock.checked,

                callNotificationsEnabled = toggles.callsEnabled,
                readReceiptsEnabled = toggles.readReceipts,
                typingIndicators = toggles.typing,
                linkPreviewEnabled = toggles.linkPreviews,
                incognitoKeyboardEnabled = toggles.incognito,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UIState())

    init {
        _uiState.update { it.copy(allowCommunityMessageRequests = isCommunityMessageRequestsEnabled) }

        prefsUiState
            .onEach { prefState ->
                _uiState.update { current ->
                    current.copy(
                        // from prefs/keyguard
                        screenLockVisible = prefState.screenLockVisible,
                        screenLockEnabled = prefState.screenLockEnabled,
                        screenLockChecked = prefState.screenLockChecked,
                        callNotificationsEnabled = prefState.callNotificationsEnabled,
                        readReceiptsEnabled = prefState.readReceiptsEnabled,
                        typingIndicators = prefState.typingIndicators,
                        linkPreviewEnabled = prefState.linkPreviewEnabled,
                        incognitoKeyboardEnabled = prefState.incognitoKeyboardEnabled,

                        // keep these as-is (not derived from prefs flow)
                        allowCommunityMessageRequests = current.allowCommunityMessageRequests,
                        showCallsWarningDialog = current.showCallsWarningDialog,
                        showCallsNotificationDialog = current.showCallsNotificationDialog,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun refreshKeyguardSecure() {
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardSecure.value = km.isKeyguardSecure
    }

    fun setScrollActions(scrollToKey: String?, scrollAndToggleKey: String?) {
        _scrollAction.update {
            it.copy(
                scrollToKey = scrollToKey,
                scrollAndToggleKey = scrollAndToggleKey
            )
        }
    }

    // Check scroll actions
    fun checkScrollActions() {
        viewModelScope.launch {
            val action = scrollAction.value

            val keyToScroll = action.scrollAndToggleKey ?: action.scrollToKey
            ?: return@launch  // nothing to do

            val index = prefItemsOrder.indexOf(keyToScroll)
            if (index >= 0) {
                delay(500L) // slight delay to make the transition less jarring
                mutableEvents.tryEmit(
                    PrivacySettingsPreferenceEvent.ScrollToIndex(index)
                )
            }

            action.scrollAndToggleKey?.let { key ->
                when (key) {
                    CALL_NOTIFICATIONS_ENABLED -> {
                        // need to do some checks before toggling
                        onCommand(ShowCallsWarningDialog)
                    }

                    else -> prefs.updateBooleanFromKey(key, true)
                }
            }

            clearScrollActions()
        }
    }

    // Scrolling and toggle is complete
    private fun clearScrollActions() {
        _scrollAction.value = ScrollAction()
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ToggleCallsNotification -> {
                prefs.setCallNotificationsEnabled(command.isEnabled)
                if (command.isEnabled && !areNotificationsEnabled(app)) {
                    _uiState.update { it.copy(showCallsNotificationDialog = true) }
                }
            }

            is Commands.ToggleLockApp -> {
                // if UI disabled it, ignore
                if (!uiState.value.screenLockEnabled) return
                prefs.setScreenLockEnabled(command.isEnabled)
                viewModelScope.launch {
                    mutableEvents.emit(PrivacySettingsPreferenceEvent.StartLockToggledService)
                }
            }

            is Commands.ToggleCommunityRequests -> {
                val newValue = !isCommunityMessageRequestsEnabled
                configFactory.withMutableUserConfigs {
                    it.userProfile.setCommunityMessageRequests(newValue)
                }
                // update state
                _uiState.update { it.copy(allowCommunityMessageRequests = newValue) }
            }

            is Commands.ToggleReadReceipts -> {
                prefs.setReadReceiptsEnabled(command.isEnabled)
            }

            is Commands.ToggleTypingIndicators -> {
                prefs.setTypingIndicatorsEnabled(command.isEnabled)
                if (!command.isEnabled) typingStatusRepository.clear()
            }

            is Commands.ToggleLinkPreviews -> {
                prefs.setLinkPreviewsEnabled(command.isEnabled)
            }

            is Commands.ToggleIncognitoKeyboard -> {
                prefs.setIncognitoKeyboardEnabled(command.isEnabled)
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

            ShowCallsWarningDialog -> {
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
        data class ToggleCallsNotification(val isEnabled: Boolean) : Commands
        data class ToggleLockApp(val isEnabled: Boolean) : Commands
        data object ToggleCommunityRequests : Commands
        data class ToggleReadReceipts(val isEnabled: Boolean) : Commands
        data class ToggleTypingIndicators(val isEnabled: Boolean) : Commands
        data class ToggleLinkPreviews(val isEnabled : Boolean) : Commands
        data class ToggleIncognitoKeyboard(val isEnabled : Boolean) : Commands

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

        data class ScrollToIndex(val index: Int) : PrivacySettingsPreferenceEvent
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

    data class ScrollAction(
        val scrollToKey: String? = null,
        val scrollAndToggleKey: String? = null
    )


    private data class ScreenLockPrefsData(
        val visible: Boolean,
        val enabled: Boolean,
        val checked: Boolean,
    )

    private data class TogglePrefsData(
        val callsEnabled: Boolean,
        val readReceipts: Boolean,
        val typing: Boolean,
        val linkPreviews: Boolean,
        val incognito: Boolean,
    )
}
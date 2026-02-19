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
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withMutableUserConfigs
import org.thoughtcrime.securesms.preferences.NotificationPreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.preferences.PrivacyPreferences
import org.thoughtcrime.securesms.preferences.SecurityPreferences
import org.thoughtcrime.securesms.preferences.compose.PrivacySettingsPreferenceViewModel.Commands.ShowCallsWarningDialog
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.areNotificationsEnabled
import javax.inject.Inject

@HiltViewModel
class PrivacySettingsPreferenceViewModel @Inject constructor(
    private val preferenceStorage: PreferenceStorage,
    private val configFactory: ConfigFactoryProtocol,
    private val app: Application,
    private val typingStatusRepository: TypingStatusRepository,
) : ViewModel() {

    private val keyguardSecure = MutableStateFlow(true)

    private val _uiEvents = MutableSharedFlow<PrivacySettingsPreferenceEvent>(
        replay = 1,
        extraBufferCapacity = 1
    )
    val uiEvents get() = _uiEvents

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState

    private val isCommunityMessageRequestsEnabled: Boolean
        get() = configFactory.withMutableUserConfigs { it.userProfile.getCommunityMessageRequests() }

    private val _scrollAction = MutableStateFlow(ScrollAction())
    val scrollAction: StateFlow<ScrollAction> = _scrollAction

    // Use this to get index for UI item. We need the index to tell the listState where to scroll
    // list things ordered by how they appear on the list
    private var prefItemsOrder = listOf(
        NotificationPreferences.CALL_NOTIFICATIONS_ENABLED.name,
        SecurityPreferences.SCREEN_LOCK.name,
        "community_message_requests",
        PrivacyPreferences.READ_RECEIPTS.name,
        PrivacyPreferences.TYPING_INDICATORS.name,
        PrivacyPreferences.LINK_PREVIEWS.name,
        PrivacyPreferences.INCOGNITO_KEYBOARD.name
    )

    private val screenLockFlow =
        combine(
            preferenceStorage.watch(viewModelScope, SecurityPreferences.SCREEN_LOCK),
            preferenceStorage.watch(viewModelScope, SecurityPreferences.PASSWORD_DISABLED),
            keyguardSecure
        ) { screenLockPref, isPasswordDisabled, keyguard ->

            val visible = isPasswordDisabled
            val enabled = isPasswordDisabled && keyguard
            val checked = if (enabled) screenLockPref else false

            ScreenLockPrefsData(visible, enabled, checked)
        }

    private val togglesFlow =
        combine(
            preferenceStorage.watch(viewModelScope, NotificationPreferences.CALL_NOTIFICATIONS_ENABLED),
            preferenceStorage.watch(viewModelScope, PrivacyPreferences.READ_RECEIPTS),
            preferenceStorage.watch(viewModelScope, PrivacyPreferences.TYPING_INDICATORS),
            preferenceStorage.watch(viewModelScope, PrivacyPreferences.LINK_PREVIEWS),
            preferenceStorage.watch(viewModelScope, PrivacyPreferences.INCOGNITO_KEYBOARD),
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
                _uiEvents.tryEmit(
                    PrivacySettingsPreferenceEvent.ScrollToIndex(index)
                )
            }

            action.scrollAndToggleKey?.let { key ->
                when (key) {
                    NotificationPreferences.CALL_NOTIFICATIONS_ENABLED.name -> {
                        // need to do some checks before toggling
                        onCommand(ShowCallsWarningDialog)
                    }

                    PrivacyPreferences.READ_RECEIPTS.name -> preferenceStorage[PrivacyPreferences.READ_RECEIPTS] = true
                    PrivacyPreferences.TYPING_INDICATORS.name -> preferenceStorage[PrivacyPreferences.TYPING_INDICATORS] = true
                    PrivacyPreferences.LINK_PREVIEWS.name -> preferenceStorage[PrivacyPreferences.LINK_PREVIEWS] = true
                    PrivacyPreferences.INCOGNITO_KEYBOARD.name -> preferenceStorage[PrivacyPreferences.INCOGNITO_KEYBOARD] = true
                    SecurityPreferences.SCREEN_LOCK.name -> preferenceStorage[SecurityPreferences.SCREEN_LOCK] = true
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
                preferenceStorage[NotificationPreferences.CALL_NOTIFICATIONS_ENABLED] = command.isEnabled
                if (command.isEnabled && !areNotificationsEnabled(app)) {
                    _uiState.update { it.copy(showCallsNotificationDialog = true) }
                }
            }

            is Commands.ToggleLockApp -> {
                // if UI disabled it, ignore
                if (!uiState.value.screenLockEnabled) return
                preferenceStorage[SecurityPreferences.SCREEN_LOCK] = command.isEnabled
                viewModelScope.launch {
                    _uiEvents.emit(PrivacySettingsPreferenceEvent.StartLockToggledService)
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
                preferenceStorage[PrivacyPreferences.READ_RECEIPTS] = command.isEnabled
            }

            is Commands.ToggleTypingIndicators -> {
                preferenceStorage[PrivacyPreferences.TYPING_INDICATORS] = command.isEnabled
                if (!command.isEnabled) typingStatusRepository.clear()
            }

            is Commands.ToggleLinkPreviews -> {
                preferenceStorage[PrivacyPreferences.LINK_PREVIEWS] = command.isEnabled
            }

            is Commands.ToggleIncognitoKeyboard -> {
                preferenceStorage[PrivacyPreferences.INCOGNITO_KEYBOARD] = command.isEnabled
            }

            Commands.AskMicPermission -> {
                // Ask for permission
                viewModelScope.launch {
                    _uiEvents.emit(PrivacySettingsPreferenceEvent.AskMicrophonePermission)
                }
            }

            Commands.NavigateToAppNotificationsSettings -> {
                viewModelScope.launch {
                    _uiEvents.emit(PrivacySettingsPreferenceEvent.OpenAppNotificationSettings)
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
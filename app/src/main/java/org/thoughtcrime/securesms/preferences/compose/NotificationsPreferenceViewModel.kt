package org.thoughtcrime.securesms.preferences.compose

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.notifications.NotificationChannelManager
import org.thoughtcrime.securesms.notifications.NotificationPreferences.CHECKED_DOZE_WHITELIST
import org.thoughtcrime.securesms.notifications.NotificationPreferences.PRIVACY
import org.thoughtcrime.securesms.notifications.NotificationPreferences.PUSH_ENABLED
import org.thoughtcrime.securesms.notifications.NotificationPreferences.SOUND_WHEN_APP_OPEN
import org.thoughtcrime.securesms.notifications.NotificationPrivacy
import org.thoughtcrime.securesms.onboarding.messagenotifications.isFastModeAvailable
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.ui.isWhitelistedFromDoze
import javax.inject.Inject

@HiltViewModel
class NotificationsPreferenceViewModel @Inject constructor(
    private val prefs: PreferenceStorage,
    val application: Application,
    private val channels: NotificationChannelManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UIState(
        fastModeEnabled = application.isFastModeAvailable()
    ))
    val uiState: StateFlow<UIState> get() = _uiState

    private val _uiEvents = MutableSharedFlow<NotificationPreferenceEvent>()
    val uiEvents: SharedFlow<NotificationPreferenceEvent> get() = _uiEvents

    val privacyOptions: List<NotificationPrivacyOption> by lazy {
        NotificationPrivacy.entries
            .map { NotificationPrivacyOption(it, application.getString(it.titleRes)) }
    }

    private val notifPrefsFlow =
        combine(
            prefs.watch(viewModelScope, CHECKED_DOZE_WHITELIST),
            prefs.watch(viewModelScope, SOUND_WHEN_APP_OPEN),
            prefs.watch(viewModelScope, PRIVACY),
        ) { checkedDozeWhitelist, soundWhenOpen, notificationPrivacy ->
            NotifPrefsData(
                checkedDozeWhitelist = checkedDozeWhitelist,
                soundWhenOpen = soundWhenOpen,
                notificationPrivacyValue = notificationPrivacy,
            )
        }

    init {
        combine(prefs.watch(viewModelScope, PUSH_ENABLED),
            notifPrefsFlow) { strategy, notif -> strategy to notif
        }.onEach { (isPushEnabled, notif) ->
            _uiState.update { old ->
                old.copy(
                    // strategy
                    fastModeSelected = isPushEnabled,
                    checkedDozeWhitelist = notif.checkedDozeWhitelist,

                    // keep the current doze whitelist status; you refresh it separately
                    isWhitelistedFromDoze = old.isWhitelistedFromDoze,

                    // style/behavior
                    soundWhenAppIsOpen = notif.soundWhenOpen,
                    notificationPrivacy = application.getString(notif.notificationPrivacyValue.titleRes),

                    // dialogs: preserve whatever the UI is currently showing
                    showWhitelistEnableDialog = old.showWhitelistEnableDialog,
                    showWhitelistDisableDialog = old.showWhitelistDisableDialog,
                    showNotificationPrivacyDialog = old.showNotificationPrivacyDialog,
                )
            }
        }.launchIn(viewModelScope)
    }

    fun onCommand(command: Commands) {
        when (command) {
            Commands.ShowWhitelistDisableDialog -> {
                _uiState.update { it.copy(showWhitelistDisableDialog = true) }
            }

            Commands.HideWhitelistDisableDialog -> {
                _uiState.update { it.copy(showWhitelistDisableDialog = false) }
            }

            Commands.ShowWhitelistEnableDialog -> {
                _uiState.update { it.copy(showWhitelistEnableDialog = true) }
            }

            Commands.HideWhitelistEnableDialog -> {
                _uiState.update { it.copy(showWhitelistEnableDialog = false) }
            }

            Commands.ShowNotificationPrivacyDialog -> {
                _uiState.update { it.copy(showNotificationPrivacyDialog = true) }
            }

            Commands.HideNotificationPrivacyDialog -> {
                hideNotificationPrivacyDialog()
            }

            is Commands.TogglePushEnabled -> {
                val currentState = uiState.value
                val isEnabled = command.isEnabled

                prefs[PUSH_ENABLED] = isEnabled

                if (!isEnabled && !currentState.checkedDozeWhitelist) {
                    _uiState.update { it.copy(showWhitelistEnableDialog = true) }
                    prefs[CHECKED_DOZE_WHITELIST] = true
                }
            }

            Commands.WhiteListClicked -> {
                // if already whitelisted, show dialog
                if (application.isWhitelistedFromDoze()) {
                    _uiState.update { it.copy(showWhitelistDisableDialog = true) }
                } else {
                    viewModelScope.launch {
                        _uiEvents.emit(
                            NotificationPreferenceEvent.NavigateToSystemBgWhitelist
                        )
                    }
                }
            }


            is Commands.ToggleSoundWhenOpen -> {
                prefs[SOUND_WHEN_APP_OPEN] = command.isEnabled
            }

            Commands.OpenSystemBgWhitelist -> {
                viewModelScope.launch {
                    _uiEvents.emit(NotificationPreferenceEvent.NavigateToSystemBgWhitelist)
                }
            }

            Commands.OpenBatteryOptimizationSettings -> {
                viewModelScope.launch {
                    _uiEvents.emit(NotificationPreferenceEvent.NavigateToBatteryOptimizationSettings)
                }
            }

            is Commands.OpenSystemNotificationSettings -> {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, application.packageName)
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, channels.getNotificationChannelId(command.channelDescription))

                viewModelScope.launch {
                    _uiEvents.emit(NotificationPreferenceEvent.NavigateToActivity(intent))
                }
            }

            is Commands.SelectNotificationPrivacyOption -> {
                prefs[PRIVACY] = command.option
                hideNotificationPrivacyDialog()
            }
        }
    }

    private fun hideNotificationPrivacyDialog() {
        _uiState.update { it.copy(showNotificationPrivacyDialog = false) }
    }

    fun refreshDozeWhitelist() {
        _uiState.update { it.copy(isWhitelistedFromDoze = application.isWhitelistedFromDoze()) }
    }

    sealed interface Commands {
        data class TogglePushEnabled(val isEnabled: Boolean) : Commands
        data object WhiteListClicked : Commands

        data class ToggleSoundWhenOpen(val isEnabled : Boolean) : Commands

        data object ShowWhitelistEnableDialog : Commands
        data object HideWhitelistEnableDialog : Commands

        data object ShowWhitelistDisableDialog : Commands
        data object HideWhitelistDisableDialog : Commands

        data object ShowNotificationPrivacyDialog : Commands

        data object HideNotificationPrivacyDialog : Commands

        data object OpenSystemBgWhitelist : Commands

        data object OpenBatteryOptimizationSettings : Commands

        data class OpenSystemNotificationSettings(
            val channelDescription: NotificationChannelManager.ChannelDescription
        ) : Commands

        data class SelectNotificationPrivacyOption(val option: NotificationPrivacy) : Commands
    }

    data class UIState(
        // Strategy
        val fastModeSelected: Boolean = false,
        val fastModeEnabled: Boolean = false,
        val isWhitelistedFromDoze: Boolean = false, // run in background
        val checkedDozeWhitelist: Boolean = false, // whitelist dialog's first time
        // style/behavior
        val soundWhenAppIsOpen: Boolean = false,
        val notificationPrivacy: String? = "",
        // dialogs
        val showWhitelistEnableDialog: Boolean = false,
        val showWhitelistDisableDialog: Boolean = false,
        val showNotificationPrivacyDialog: Boolean = false
    )

    sealed interface NotificationPreferenceEvent {
        data class NavigateToActivity(val intent: Intent) : NotificationPreferenceEvent

        data object NavigateToBatteryOptimizationSettings : NotificationPreferenceEvent

        data object NavigateToSystemBgWhitelist : NotificationPreferenceEvent

    }

    data class NotificationPrivacyOption(val value: NotificationPrivacy, val label: String)

    private data class NotifPrefsData(
        val checkedDozeWhitelist: Boolean,
        val soundWhenOpen: Boolean,
        val notificationPrivacyValue: NotificationPrivacy,
    )
}

package org.thoughtcrime.securesms.preferences.compose

import android.app.Application
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.observeBooleanKey
import org.session.libsession.utilities.observeStringKey
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.ui.isWhitelistedFromDoze
import javax.inject.Inject

@HiltViewModel
class NotificationsPreferenceViewModel @Inject constructor(
    var prefs: TextSecurePreferences,
    val application: Application,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState

    private val mutableEvents = MutableSharedFlow<NotificationPreferenceEvent>()
    val events get() = mutableEvents

    val privacyOptions: List<NotificationPrivacyOption> by lazy {
        val labels = application.resources.getStringArray(R.array.pref_notification_privacy_entries)
        val values = application.resources.getStringArray(R.array.pref_notification_privacy_values)
        values.zip(labels).map { (value, label) -> NotificationPrivacyOption(value, label) }
    }

    private val notificationBehavior: StateFlow<UIState> =
        combine(
            prefs.pushEnabled,
            prefs.observeStringKey(TextSecurePreferences.RINGTONE_PREF, default = null),
            prefs.observeBooleanKey(TextSecurePreferences.SOUND_WHEN_OPEN, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.VIBRATE_PREF, default = true),
            prefs.observeStringKey(TextSecurePreferences.NOTIFICATION_PRIVACY_PREF, default = "all")
        ) { isPushEnabled, ringtone, soundWhenOpen, vibrate, notificationPrivacy ->
            UIState(
                isPushEnabled = isPushEnabled,
                ringtone = prefs.getNotificationRingtone().toString(),
                soundWhenAppIsOpen = soundWhenOpen,
                vibrate = vibrate,
                notificationPrivacy = notificationPrivacy
            )
        }.stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, UIState())

    init {
        viewModelScope.launch {
            notificationBehavior.collect { values ->
                _uiState.update {
                    it.copy(
                        isPushEnabled = values.isPushEnabled,
                        checkedDozeWhitelist = values.checkedDozeWhitelist,
                        ringtone = getRingtoneName(values.ringtone),
                        soundWhenAppIsOpen = values.soundWhenAppIsOpen,
                        vibrate = values.vibrate,
                        notificationPrivacy = privacyOptions
                            .find { option -> option.value == values.notificationPrivacy }
                            ?.label
                    )
                }
            }
        }
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

            Commands.TogglePushEnabled -> {
                val currentState = uiState.value
                val newValue = !uiState.value.isPushEnabled

                prefs.setPushEnabled(newValue)

                if (!newValue && !currentState.checkedDozeWhitelist) {
                    _uiState.update { it.copy(showWhitelistEnableDialog = true) }
                    prefs.setHasCheckedDozeWhitelist(true)
                }
            }

            Commands.WhiteListClicked -> {
                // if already whitelisted, show dialog
                if (application.isWhitelistedFromDoze()) {
                    _uiState.update { it.copy(showWhitelistDisableDialog = true) }
                } else {
                    viewModelScope.launch {
                        mutableEvents.emit(
                            NotificationPreferenceEvent.NavigateToSystemBgWhitelist
                        )
                    }
                }
            }

            Commands.RingtoneClicked -> {
                val current = prefs.getNotificationRingtone()
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                intent.putExtra(
                    RingtoneManager.EXTRA_RINGTONE_TYPE,
                    RingtoneManager.TYPE_NOTIFICATION
                )
                intent.putExtra(
                    RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                    Settings.System.DEFAULT_NOTIFICATION_URI
                )
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)

                viewModelScope.launch {
                    mutableEvents.emit(
                        NotificationPreferenceEvent.StartRingtoneActivityForResult(
                            intent
                        )
                    )
                }
            }

            is Commands.SetRingtone -> {
                var ringtoneUri = command.uri
                if (Settings.System.DEFAULT_NOTIFICATION_URI == ringtoneUri) {
                    NotificationChannels.updateMessageRingtone(application, ringtoneUri)
                    prefs.removeNotificationRingtone()
                } else {
                    ringtoneUri = command.uri ?: Uri.EMPTY
                    NotificationChannels.updateMessageRingtone(application, ringtoneUri)
                    prefs.setNotificationRingtone(ringtoneUri.toString())
                }
            }

            Commands.ToggleSoundWhenOpen -> {
                prefs.setSoundWhenAppIsOpenEnabled(!uiState.value.soundWhenAppIsOpen)
            }

            Commands.ToggleVibrate -> {
                prefs.setNotificationVibrateEnabled(!uiState.value.vibrate)
            }

            Commands.OpenSystemBgWhitelist -> {
                viewModelScope.launch {
                    mutableEvents.emit(NotificationPreferenceEvent.NavigateToSystemBgWhitelist)
                }
            }

            Commands.OpenBatteryOptimizationSettings -> {
                viewModelScope.launch {
                    mutableEvents.emit(NotificationPreferenceEvent.NavigateToBatteryOptimizationSettings)
                }
            }

            Commands.OpenSystemNotificationSettings -> {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                intent.putExtra(
                    Settings.EXTRA_CHANNEL_ID,
                    NotificationChannels.getMessagesChannel(application)
                )
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, application.packageName)

                viewModelScope.launch {
                    mutableEvents.emit(NotificationPreferenceEvent.NavigateToActivity(intent))
                }
            }

            is Commands.SelectNotificationPrivacyOption -> {
                prefs.setNotificationPrivacy(command.option)
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
        data object TogglePushEnabled : Commands
        data object WhiteListClicked : Commands

        data object RingtoneClicked : Commands
        data class SetRingtone(val uri: Uri?) : Commands
        data object ToggleSoundWhenOpen : Commands
        data object ToggleVibrate : Commands

        data object ShowWhitelistEnableDialog : Commands
        data object HideWhitelistEnableDialog : Commands

        data object ShowWhitelistDisableDialog : Commands
        data object HideWhitelistDisableDialog : Commands

        data object ShowNotificationPrivacyDialog : Commands

        data object HideNotificationPrivacyDialog : Commands

        data object OpenSystemBgWhitelist : Commands

        data object OpenBatteryOptimizationSettings : Commands

        data object OpenSystemNotificationSettings : Commands

        data class SelectNotificationPrivacyOption(val option: String) : Commands
    }

    data class UIState(
        // Strategy
        val isPushEnabled: Boolean = false,
        val isWhitelistedFromDoze: Boolean = false, // run in background
        val checkedDozeWhitelist: Boolean = false, // whitelist dialog's first time
        // style/behavior
        val ringtone: String? = null,
        val soundWhenAppIsOpen: Boolean = false,
        val vibrate: Boolean = false,
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

        data class StartRingtoneActivityForResult(val intent: Intent) : NotificationPreferenceEvent
    }

    data class NotificationPrivacyOption(val value: String, val label: String)

    private fun getRingtoneName(string: String?): String {
        var uriString = string
        if (uriString != null && string.startsWith("file:")) {
            uriString = Settings.System.DEFAULT_NOTIFICATION_URI.toString()
        }

        val ringtoneUri = uriString?.toUri()
        if (ringtoneUri == Uri.EMPTY) return application.getString(R.string.none)

        return runCatching {
            RingtoneManager.getRingtone(application, ringtoneUri)?.getTitle(application)
        }.getOrNull() ?: application.getString(R.string.unknown)
    }
}
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
import kotlin.Boolean

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

    private val notifPrefsFlow =
        combine(
            prefs.observeBooleanKey(TextSecurePreferences.HAS_CHECKED_DOZE_WHITELIST, default = false),
            prefs.observeStringKey(TextSecurePreferences.RINGTONE_PREF, default = null),
            prefs.observeBooleanKey(TextSecurePreferences.SOUND_WHEN_OPEN, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.VIBRATE_PREF, default = true),
            prefs.observeStringKey(TextSecurePreferences.NOTIFICATION_PRIVACY_PREF, default = "all"),
        ) { checkedDozeWhitelist, ringtonePrefString, soundWhenOpen, vibrate, notificationPrivacy ->
            NotifPrefsData(
                checkedDozeWhitelist = checkedDozeWhitelist,
                ringtoneUriString = ringtonePrefString,
                soundWhenOpen = soundWhenOpen,
                vibrate = vibrate,
                notificationPrivacyValue = notificationPrivacy ?: "all"
            )
        }

    init {
        combine(prefs.pushEnabled, notifPrefsFlow) { strategy, notif ->
            strategy to notif
        }.onEach { (isPushEnabled, notif) ->
            _uiState.update { old ->
                old.copy(
                    // strategy
                    isPushEnabled = isPushEnabled,
                    checkedDozeWhitelist = notif.checkedDozeWhitelist,

                    // keep the current doze whitelist status; you refresh it separately
                    isWhitelistedFromDoze = old.isWhitelistedFromDoze,

                    // style/behavior
                    ringtone = getRingtoneName(notif.ringtoneUriString),
                    soundWhenAppIsOpen = notif.soundWhenOpen,
                    vibrate = notif.vibrate,
                    notificationPrivacy = privacyOptions
                        .firstOrNull { it.value == notif.notificationPrivacyValue }
                        ?.label
                        ?: "",

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

                prefs.setPushEnabled(isEnabled)

                if (!isEnabled && !currentState.checkedDozeWhitelist) {
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

            is Commands.ToggleSoundWhenOpen -> {
                prefs.setSoundWhenAppIsOpenEnabled(command.isEnabled)
            }

            is Commands.ToggleVibrate -> {
                prefs.setNotificationVibrateEnabled(command.isEnabled)
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
        data class TogglePushEnabled(val isEnabled: Boolean) : Commands
        data object WhiteListClicked : Commands

        data object RingtoneClicked : Commands
        data class SetRingtone(val uri: Uri?) : Commands
        data class ToggleSoundWhenOpen(val isEnabled : Boolean) : Commands
        data class ToggleVibrate(val isEnabled : Boolean) : Commands

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

    private data class NotifPrefsData(
        val checkedDozeWhitelist: Boolean,
        val ringtoneUriString: String?, // raw pref string (can be null)
        val soundWhenOpen: Boolean,
        val vibrate: Boolean,
        val notificationPrivacyValue: String,
    )
}
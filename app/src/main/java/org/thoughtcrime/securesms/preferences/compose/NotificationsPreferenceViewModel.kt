package org.thoughtcrime.securesms.preferences.compose

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.observeBooleanKey
import org.session.libsession.utilities.observeStringKey
import org.thoughtcrime.securesms.ui.isWhitelistedFromDoze
import javax.inject.Inject

@HiltViewModel
class NotificationsPreferenceViewModel @Inject constructor(
    var prefs: TextSecurePreferences,
    val application: Application,
) : ViewModel() {

    val _uiState: StateFlow<UIState> =
        combine(
            prefs.pushEnabled,
            prefs.observeBooleanKey(
                TextSecurePreferences.IS_WHITELIST_BACKGROUND,
                default = false
            ),
            prefs.observeBooleanKey(
                TextSecurePreferences.HAS_CHECKED_DOZE_WHITELIST,
                default = false
            ),
            prefs.observeBooleanKey(TextSecurePreferences.SOUND_WHEN_OPEN, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.VIBRATE_PREF, default = true),
            prefs.observeStringKey(
                TextSecurePreferences.NOTIFICATION_PRIVACY_PREF,
                default = "all"
            ),
            prefs.observeStringKey(TextSecurePreferences.RINGTONE_PREF, default = null),
        ) { values ->
            UIState(
                isPushEnabled = values[0] as Boolean,
                isWhiteList = values[1] as Boolean,
                checkedDozeWhitelist = values[2] as Boolean,
                soundWhenAppIsOpen = values[3] as Boolean,
                vibrate = values[4] as Boolean,
                notificationPrivacy = values[5] as String?,
                ringtone = values[6] as Uri?,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = UIState(
                isPushEnabled = prefs.pushEnabled.value,
                isWhiteList = prefs.isWhiteListBackground(),
                checkedDozeWhitelist = prefs.hasCheckedDozeWhitelist(),
                soundWhenAppIsOpen = prefs.isSoundWhenAppIsOpenEnabled(),
                vibrate = prefs.isNotificationVibrateEnabled(),
                notificationPrivacy = prefs.getNotificationPrivacy().preference,
                ringtone = prefs.getNotificationRingtone(),
            )
        )


    fun onCommand(command: Commands) {
        when (command) {
            Commands.ShowWhitelistDisableDialog -> {}
            Commands.HideWhitelistDisableDialog -> {}
            Commands.ShowWhitelistEnableDialog -> {}
            Commands.HideWhitelistEnableDialog -> {}
            Commands.TogglePushEnabled -> {}
            Commands.WhiteListClicked -> {
                if(application.isWhitelistedFromDoze()){
                } else {
                }
            }
            Commands.RingtoneClicked -> {}
            Commands.ToggleSoundWhenOpen -> {}
            Commands.ToggleVibrate -> {}
        }
    }

    sealed interface Commands {
        object TogglePushEnabled : Commands
        object WhiteListClicked : Commands

        object RingtoneClicked : Commands
        object ToggleSoundWhenOpen : Commands
        object ToggleVibrate : Commands

        object ShowWhitelistEnableDialog : Commands
        object HideWhitelistEnableDialog : Commands

        object ShowWhitelistDisableDialog : Commands
        object HideWhitelistDisableDialog : Commands
    }

    data class UIState(
        val fcmSummary: String = "",
        val isPushEnabled: Boolean = false,
        val isWhiteList: Boolean = false, // run in background
        val checkedDozeWhitelist: Boolean = false, // whitelist dialog's first time
        val ringtone: Uri? = null,
        val soundWhenAppIsOpen: Boolean = false,
        val vibrate: Boolean = false,
        val notificationPrivacy: String? = "",
        // dialogs
        val showWhitelistEnableDialog: Boolean = false,
        val showWhitelistDisableDialog: Boolean = false
    )

}
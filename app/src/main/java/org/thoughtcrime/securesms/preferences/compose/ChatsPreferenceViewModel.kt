package org.thoughtcrime.securesms.preferences.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.observeBooleanKey
import javax.inject.Inject

@HiltViewModel
class ChatsPreferenceViewModel @Inject constructor(
    var prefs: TextSecurePreferences
) : ViewModel() {

    val uiState: StateFlow<UIState> =
        combine(
            prefs.observeBooleanKey(TextSecurePreferences.THREAD_TRIM_ENABLED, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.SEND_WITH_ENTER, default = false),
            prefs.observeBooleanKey(TextSecurePreferences.AUTOPLAY_AUDIO_MESSAGES, default = false),
        ) { trim, enter, autoplay ->
            UIState(
                trimThreads = trim,
                sendWithEnter = enter,
                autoplayAudioMessage = autoplay
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = UIState(
                trimThreads = prefs.isThreadLengthTrimmingEnabled(),
                sendWithEnter = prefs.isSendWithEnterEnabled(),
                autoplayAudioMessage = prefs.isAutoplayAudioMessagesEnabled(),
            )
        )

    fun onCommand(command: Commands) {
        val currentValue = uiState.value
        when (command) {
            Commands.ToggleTrimThreads -> prefs.setThreadLengthTrimmingEnabled(!currentValue.trimThreads)
            Commands.ToggleSendWithEnter -> prefs.setSendWithEnterEnabled(!currentValue.sendWithEnter)
            Commands.ToggleAutoplayAudioMessages -> prefs.setAutoplayAudioMessages(!currentValue.autoplayAudioMessage)
        }
    }

    sealed interface Commands {
        data object ToggleTrimThreads : Commands
        data object ToggleSendWithEnter : Commands
        data object ToggleAutoplayAudioMessages : Commands
    }

    data class UIState(
        val trimThreads: Boolean = false,
        val sendWithEnter: Boolean = false,
        val autoplayAudioMessage: Boolean = false
    )
}
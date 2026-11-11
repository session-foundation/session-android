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
            prefs.observeBooleanKey(TextSecurePreferences.USE_SYSTEM_EMOJI, default = true),
        ) { trim, enter, autoplay, systemEmoji ->
            UIState(
                trimThreads = trim,
                sendWithEnter = enter,
                autoplayAudioMessage = autoplay,
                useSystemEmoji = systemEmoji
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = UIState(
                trimThreads = prefs.isThreadLengthTrimmingEnabled(),
                sendWithEnter = prefs.isSendWithEnterEnabled(),
                autoplayAudioMessage = prefs.isAutoplayAudioMessagesEnabled(),
                useSystemEmoji = prefs.isUseSystemEmojiEnabled(),
            )
        )

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ToggleTrimThreads -> prefs.setThreadLengthTrimmingEnabled(command.isEnabled)
            is Commands.ToggleSendWithEnter -> prefs.setSendWithEnterEnabled(command.isEnabled)
            is Commands.ToggleAutoplayAudioMessages -> prefs.setAutoplayAudioMessages(command.isEnabled)
            is Commands.ToggleUseSystemEmoji -> prefs.setUseSystemEmojiEnabled(command.isEnabled)
        }
    }

    sealed interface Commands {
        data class ToggleTrimThreads(val isEnabled: Boolean) : Commands
        data class ToggleSendWithEnter(val isEnabled: Boolean) : Commands
        data class ToggleAutoplayAudioMessages(val isEnabled: Boolean) : Commands
        data class ToggleUseSystemEmoji(val isEnabled: Boolean) : Commands
    }

    data class UIState(
        val trimThreads: Boolean = false,
        val sendWithEnter: Boolean = false,
        val autoplayAudioMessage: Boolean = false,
        val useSystemEmoji: Boolean = true
    )
}
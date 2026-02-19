package org.thoughtcrime.securesms.preferences.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.preferences.MessagingPreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject

@HiltViewModel
class ChatsPreferenceViewModel @Inject constructor(
    private val textSecurePreferences: TextSecurePreferences,
    private val preferenceStorage: PreferenceStorage
) : ViewModel() {

    val uiState: StateFlow<UIState> =
        combine(
            preferenceStorage.watch(viewModelScope, MessagingPreferences.THREAD_TRIM_ENABLED),
            preferenceStorage.watch(viewModelScope, MessagingPreferences.SEND_WITH_ENTER),
            preferenceStorage.watch(viewModelScope, MessagingPreferences.AUTOPLAY_AUDIO_MESSAGES),
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
                trimThreads = preferenceStorage[MessagingPreferences.THREAD_TRIM_ENABLED],
                sendWithEnter = preferenceStorage[MessagingPreferences.SEND_WITH_ENTER],
                autoplayAudioMessage = preferenceStorage[MessagingPreferences.AUTOPLAY_AUDIO_MESSAGES],
            )
        )

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ToggleTrimThreads -> preferenceStorage[MessagingPreferences.THREAD_TRIM_ENABLED] = command.isEnabled
            is Commands.ToggleSendWithEnter -> preferenceStorage[MessagingPreferences.SEND_WITH_ENTER] = command.isEnabled
            is Commands.ToggleAutoplayAudioMessages -> preferenceStorage[MessagingPreferences.AUTOPLAY_AUDIO_MESSAGES] = command.isEnabled
        }
    }

    sealed interface Commands {
        data class ToggleTrimThreads(val isEnabled: Boolean) : Commands
        data class ToggleSendWithEnter(val isEnabled: Boolean) : Commands
        data class ToggleAutoplayAudioMessages(val isEnabled: Boolean) : Commands
    }

    data class UIState(
        val trimThreads: Boolean = false,
        val sendWithEnter: Boolean = false,
        val autoplayAudioMessage: Boolean = false
    )
}
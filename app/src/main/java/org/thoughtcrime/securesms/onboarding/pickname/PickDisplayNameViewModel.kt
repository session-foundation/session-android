package org.thoughtcrime.securesms.onboarding.pickname

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.messaging.messages.ProfileUpdateHandler
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences

@HiltViewModel(assistedFactory = PickDisplayNameViewModel.Factory::class)
class PickDisplayNameViewModel @AssistedInject constructor(
    @Assisted private val loadFailed: Boolean,
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactoryProtocol,
): ViewModel() {
    private val isCreateAccount = !loadFailed

    private val _states = MutableStateFlow(if (loadFailed) pickNewNameState() else State())
    val states = _states.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    fun onContinue() {
        _states.update { it.copy(displayName = it.displayName.trim()) }

        val displayName = _states.value.displayName

        when {
            displayName.isEmpty() -> { _states.update { it.copy(isTextErrorColor = true, error = R.string.displayNameErrorDescription) } }
            displayName.toByteArray().size > ProfileUpdateHandler.MAX_PROFILE_NAME_LENGTH -> { _states.update { it.copy(isTextErrorColor = true, error = R.string.displayNameErrorDescriptionShorter) } }
            else -> {
                // success - clear the error as we can still see it during the transition to the
                // next screen.
                _states.update { it.copy(isTextErrorColor = false, error = null) }

                viewModelScope.launch(Dispatchers.IO) {
                    if (loadFailed) {
                        configFactory.withMutableUserConfigs {
                            it.userProfile.setName(displayName)
                        }

                        _events.emit(Event.LoadAccountComplete)
                    } else _events.emit(Event.CreateAccount(displayName))
                }
            }
        }
    }

    fun onChange(value: String) {
        _states.update { state ->
            state.copy(
                displayName = value,
                isTextErrorColor = false
            )
        }
    }

    /**
     * @return [true] if the back press was handled.
     */
    fun onBackPressed(): Boolean = isCreateAccount.also {
        if (it) _states.update { it.copy(showDialog = true) }
    }

    fun dismissDialog() {
        _states.update { it.copy(showDialog = false) }
    }

    @AssistedFactory
    interface Factory {
        fun create(loadFailed: Boolean): PickDisplayNameViewModel
    }
}

data class State(
    @StringRes val title: Int = R.string.displayNamePick,
    @StringRes val description: Int = R.string.displayNameDescription,
    val showDialog: Boolean = false,
    val isTextErrorColor: Boolean = false,
    @StringRes val error: Int? = null,
    val displayName: String = ""
)

fun pickNewNameState() = State(
    title = R.string.displayNameNew,
    description = R.string.displayNameErrorNew
)

sealed interface Event {
    class CreateAccount(val profileName: String): Event
    object LoadAccountComplete: Event
}

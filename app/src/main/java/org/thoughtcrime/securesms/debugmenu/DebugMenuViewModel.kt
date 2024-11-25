package org.thoughtcrime.securesms.debugmenu

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import javax.inject.Inject

@HiltViewModel
class DebugMenuViewModel @Inject constructor(
    private val application: Application,
    private val textSecurePreferences: TextSecurePreferences,
    private val configFactory: ConfigFactory
) : ViewModel() {
    private val TAG = "DebugMenu"

    private val _uiState = MutableStateFlow(
        UIState(
            currentEnvironment = textSecurePreferences.getEnvironment().label,
            environments = Environment.entries.map { it.label },
            snackMessage = null,
            showEnvironmentWarningDialog = false,
            showEnvironmentLoadingDialog = false,
            hideMessageRequests = textSecurePreferences.hasHiddenMessageRequests(),
            hideNoteToSelf = textSecurePreferences.hasHiddenNoteToSelf()
        )
    )
    val uiState: StateFlow<UIState>
        get() = _uiState

    private var temporaryEnv: Environment? = null

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ChangeEnvironment -> changeEnvironment()

            is Commands.HideEnvironmentWarningDialog -> _uiState.value =
                _uiState.value.copy(showEnvironmentWarningDialog = false)

            is Commands.ShowEnvironmentWarningDialog ->
                showEnvironmentWarningDialog(command.environment)

            is Commands.HideMessageRequest -> {
                textSecurePreferences.setHasHiddenMessageRequests(command.hide)
                _uiState.value = _uiState.value.copy(hideMessageRequests = command.hide)
            }

            is Commands.HideNoteToSelf -> {
                textSecurePreferences.setHasHiddenNoteToSelf(command.hide)
                configFactory.withMutableUserConfigs {
                    it.userProfile.setNtsPriority(if(command.hide) PRIORITY_HIDDEN else PRIORITY_VISIBLE)
                }
                _uiState.value = _uiState.value.copy(hideNoteToSelf = command.hide)
            }
        }
    }

    private fun showEnvironmentWarningDialog(environment: String) {
        if(environment == _uiState.value.currentEnvironment) return
        val env = Environment.entries.firstOrNull { it.label == environment } ?: return

        temporaryEnv = env

        _uiState.value = _uiState.value.copy(showEnvironmentWarningDialog = true)
    }

    private fun changeEnvironment() {
        val env = temporaryEnv ?: return

        // show a loading state
        _uiState.value = _uiState.value.copy(
            showEnvironmentWarningDialog = false,
            showEnvironmentLoadingDialog = true
        )

        // clear remote and local data, then restart the app
        viewModelScope.launch {
            ApplicationContext.getInstance(application).clearAllData().let { success ->
                if(success){
                    // save the environment
                    textSecurePreferences.setEnvironment(env)
                    delay(500)
                    ApplicationContext.getInstance(application).restartApplication()
                } else {
                    _uiState.value = _uiState.value.copy(
                        showEnvironmentWarningDialog = false,
                        showEnvironmentLoadingDialog = false
                    )
                    Log.e(TAG, "Failed to force sync when deleting data")
                    _uiState.value = _uiState.value.copy(snackMessage = "Sorry, something went wrong...")
                    return@launch
                }
            }
        }
    }

    data class UIState(
        val currentEnvironment: String,
        val environments: List<String>,
        val snackMessage: String?,
        val showEnvironmentWarningDialog: Boolean,
        val showEnvironmentLoadingDialog: Boolean,
        val hideMessageRequests: Boolean,
        val hideNoteToSelf: Boolean
    )

    sealed class Commands {
        object ChangeEnvironment : Commands()
        data class ShowEnvironmentWarningDialog(val environment: String) : Commands()
        object HideEnvironmentWarningDialog : Commands()
        data class HideMessageRequest(val hide: Boolean) : Commands()
        data class HideNoteToSelf(val hide: Boolean) : Commands()
    }
}
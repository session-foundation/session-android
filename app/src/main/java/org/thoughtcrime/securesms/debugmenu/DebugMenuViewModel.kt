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
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class DebugMenuViewModel @Inject constructor(
    private val application: Application,
    private val textSecurePreferences: TextSecurePreferences,
    private val configFactory: ConfigFactory,
    private val deprecationManager: LegacyGroupDeprecationManager,
) : ViewModel() {
    private val TAG = "DebugMenu"

    private val _uiState = MutableStateFlow(
        UIState(
            currentEnvironment = textSecurePreferences.getEnvironment().label,
            environments = Environment.entries.map { it.label },
            snackMessage = null,
            showEnvironmentWarningDialog = false,
            showLoadingDialog = false,
            showDeprecatedStateWarningDialog = false,
            hideMessageRequests = textSecurePreferences.hasHiddenMessageRequests(),
            hideNoteToSelf = textSecurePreferences.hasHiddenNoteToSelf(),
            forceDeprecationState = deprecationManager.deprecationStateOverride.value,
            availableDeprecationState = listOf(null) + LegacyGroupDeprecationManager.DeprecationState.entries.toList(),
            deprecatedTime = deprecationManager.deprecatedTime.value,
            deprecatingStartTime = deprecationManager.deprecatingStartTime.value,
        )
    )
    val uiState: StateFlow<UIState>
        get() = _uiState

    private var temporaryEnv: Environment? = null

    private var temporaryDeprecatedState: LegacyGroupDeprecationManager.DeprecationState? = null

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

            is Commands.OverrideDeprecationState -> {
                if(temporaryDeprecatedState == null) return

                _uiState.value = _uiState.value.copy(forceDeprecationState = temporaryDeprecatedState,
                    showLoadingDialog = true)

                deprecationManager.overrideDeprecationState(temporaryDeprecatedState)


                // restart app
                viewModelScope.launch {
                    delay(500) // giving time to save data
                    ApplicationContext.getInstance(application).restartApplication()
                }
            }

            is Commands.OverrideDeprecatedTime -> {
                deprecationManager.overrideDeprecatedTime(command.time)
                _uiState.value = _uiState.value.copy(deprecatedTime = command.time)
            }

            is Commands.OverrideDeprecatingStartTime -> {
                deprecationManager.overrideDeprecatingStartTime(command.time)
                _uiState.value = _uiState.value.copy(deprecatingStartTime = command.time)
            }

            is Commands.HideDeprecationChangeDialog ->
                _uiState.value = _uiState.value.copy(showDeprecatedStateWarningDialog = false)

            is Commands.ShowDeprecationChangeDialog ->
                showDeprecatedStateWarningDialog(command.state)
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
            showLoadingDialog = true
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
                        showLoadingDialog = false
                    )
                    Log.e(TAG, "Failed to force sync when deleting data")
                    _uiState.value = _uiState.value.copy(snackMessage = "Sorry, something went wrong...")
                    return@launch
                }
            }
        }
    }

    private fun showDeprecatedStateWarningDialog(state: LegacyGroupDeprecationManager.DeprecationState?) {
        if(state == _uiState.value.forceDeprecationState) return

        temporaryDeprecatedState = state

        _uiState.value = _uiState.value.copy(showDeprecatedStateWarningDialog = true)
    }

    data class UIState(
        val currentEnvironment: String,
        val environments: List<String>,
        val snackMessage: String?,
        val showEnvironmentWarningDialog: Boolean,
        val showLoadingDialog: Boolean,
        val showDeprecatedStateWarningDialog: Boolean,
        val hideMessageRequests: Boolean,
        val hideNoteToSelf: Boolean,
        val forceDeprecationState: LegacyGroupDeprecationManager.DeprecationState?,
        val availableDeprecationState: List<LegacyGroupDeprecationManager.DeprecationState?>,
        val deprecatedTime: ZonedDateTime,
        val deprecatingStartTime: ZonedDateTime,
    )

    sealed class Commands {
        object ChangeEnvironment : Commands()
        data class ShowEnvironmentWarningDialog(val environment: String) : Commands()
        object HideEnvironmentWarningDialog : Commands()
        data class HideMessageRequest(val hide: Boolean) : Commands()
        data class HideNoteToSelf(val hide: Boolean) : Commands()
        data class ShowDeprecationChangeDialog(val state: LegacyGroupDeprecationManager.DeprecationState?) : Commands()
        object HideDeprecationChangeDialog : Commands()
        object OverrideDeprecationState : Commands()
        data class OverrideDeprecatedTime(val time: ZonedDateTime) : Commands()
        data class OverrideDeprecatingStartTime(val time: ZonedDateTime) : Commands()
    }
}
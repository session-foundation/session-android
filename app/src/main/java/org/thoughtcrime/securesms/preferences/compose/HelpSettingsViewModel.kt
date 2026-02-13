package org.thoughtcrime.securesms.preferences.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.ui.components.LogExporter
import javax.inject.Inject

@HiltViewModel
class HelpSettingsViewModel @Inject constructor(
    val exporter: LogExporter
) : ViewModel() {
    private val _uiEvents = MutableSharedFlow<HelpSettingsEvent>()
    val uiEvents get() = _uiEvents

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.OpenUrl -> {
                viewModelScope.launch {
                    _uiEvents.emit(HelpSettingsEvent.HandleUrl(command.urlString))
                }
            }

            is Commands.ExportLogs -> {
                viewModelScope.launch {
                    //Check for storage permissions first
                    _uiEvents.emit(HelpSettingsEvent.HandleExportLogs)
                }
            }

            Commands.ShowExportDialog -> {
                _uiState.update { it.copy(showExportDialog = true) }
            }

            Commands.HideExportDialog -> {
                _uiState.update { it.copy(showExportDialog = false) }
            }
        }
    }

    data class UIState(
        val showExportDialog: Boolean = false
    )

    sealed interface Commands {
        data object ExportLogs : Commands
        data class OpenUrl(val urlString: String) : Commands

        data object ShowExportDialog : Commands

        data object HideExportDialog : Commands
    }

    sealed interface HelpSettingsEvent {
        data object HandleExportLogs : HelpSettingsEvent
        data class HandleUrl(val urlString: String) : HelpSettingsEvent
    }
}
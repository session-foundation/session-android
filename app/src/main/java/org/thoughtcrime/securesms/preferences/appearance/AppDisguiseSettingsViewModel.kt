package org.thoughtcrime.securesms.preferences.appearance

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.thoughtcrime.securesms.disguise.AppDisguiseManager
import javax.inject.Inject

@HiltViewModel
class AppDisguiseSettingsViewModel @Inject constructor(
    private val manager: AppDisguiseManager
) : ViewModel() {
    // Whether the app disguise is enabled
    val isOn: StateFlow<Boolean> get() = manager.isOn

    // The contents of the selection items
    val alternativeIcons: StateFlow<List<IconAndName>> = combine(
        manager.allAppAliases,
        manager.selectedAppAliasName,
        manager.isOn
    ) { aliases, selected, on ->
        aliases.mapNotNull { alias ->
            IconAndName(
                id = alias.activityAliasName,
                icon = alias.appIcon ?: return@mapNotNull null,
                name = alias.appName ?: return@mapNotNull null,
                selected = on && alias.activityAliasName == selected
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private val mutableConfirmDialogState = MutableStateFlow(ConfirmDialogState(null, false))
    val confirmDialogState: StateFlow<ConfirmDialogState> get() = mutableConfirmDialogState

    fun onCommand(command: Command) {
        when (command) {
            is Command.IconSelectConfirmed -> {
                mutableConfirmDialogState.value = ConfirmDialogState(null, false)
                if (command.id == null) {
                    manager.setOn(false)
                } else {
                    manager.setOn(true)
                    manager.setSelectedAliasName(command.id)
                }
            }

            Command.IconSelectDismissed -> {
                mutableConfirmDialogState.value = ConfirmDialogState(null, false)
            }

            is Command.IconSelected -> {
                if (!isOn.value || command.id != manager.selectedAppAliasName.value) {
                    mutableConfirmDialogState.value = ConfirmDialogState(
                        id = command.id,
                        showDialog = true
                    )
                }
            }

            is Command.ToggleClicked -> {
                if (isOn.value == command.on) return

                mutableConfirmDialogState.value = ConfirmDialogState(
                    id = if (command.on) manager.selectedAppAliasName.value ?: alternativeIcons.value.firstOrNull()?.id else null,
                    showDialog = true
                )
            }
        }
    }

    data class IconAndName(
        val id: String,
        @DrawableRes val icon: Int,
        @StringRes val name: Int,
        val selected: Boolean,
    )

    data class ConfirmDialogState(val id: String?, val showDialog: Boolean)

    sealed interface Command {
        data class IconSelected(val id: String) : Command
        data class IconSelectConfirmed(val id: String?) : Command
        data object IconSelectDismissed : Command
        data class ToggleClicked(val on: Boolean) : Command
    }
}
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
    // The contents of the selection items
    val iconList: StateFlow<List<IconAndName>> = combine(
        manager.allAppAliases,
        manager.selectedAppAliasName,
    ) { aliases, selected ->
        aliases
            .sortedByDescending { it.defaultEnabled } // The default enabled alias must be first
            .mapNotNull { alias ->
                IconAndName(
                    id = alias.activityAliasName,
                    icon = alias.appIcon ?: return@mapNotNull null,
                    name = alias.appName ?: return@mapNotNull null,
                    selected = selected?.let { alias.activityAliasName == it } ?: alias.defaultEnabled
                )
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private val mutableConfirmDialogState = MutableStateFlow<ConfirmDialogState?>(null)
    val confirmDialogState: StateFlow<ConfirmDialogState?> get() = mutableConfirmDialogState

    fun onCommand(command: Command) {
        when (command) {
            is Command.IconSelectConfirmed -> {
                mutableConfirmDialogState.value = null
                manager.setSelectedAliasName(command.id)
            }

            Command.IconSelectDismissed -> {
                mutableConfirmDialogState.value = null
            }

            is Command.IconSelected -> {
                if (command.id != manager.selectedAppAliasName.value) {
                    mutableConfirmDialogState.value = ConfirmDialogState(id = command.id,)
                }
            }
        }
    }

    data class IconAndName(
        val id: String,
        @DrawableRes val icon: Int,
        @StringRes val name: Int,
        val selected: Boolean,
    )

    data class ConfirmDialogState(val id: String)

    sealed interface Command {
        data class IconSelected(val id: String) : Command
        data class IconSelectConfirmed(val id: String) : Command
        data object IconSelectDismissed : Command
    }
}
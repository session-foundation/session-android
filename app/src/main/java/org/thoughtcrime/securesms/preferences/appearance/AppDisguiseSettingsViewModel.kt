package org.thoughtcrime.securesms.preferences.appearance

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

    // Whether to show the selection items
    val showAlternativeIconList: StateFlow<Boolean> get() = isOn

    // The contents of the selection items
    val alternativeIcons: StateFlow<List<IconAndName>> = combine(
        manager.allAppAliases,
        manager.selectedAppAliasName
    ) { aliases, selected ->
        aliases.mapNotNull { alias ->
            IconAndName(
                id = alias.activityAliasName,
                icon = alias.appIcon ?: return@mapNotNull null,
                name = alias.appName ?: return@mapNotNull null,
                selected = alias.activityAliasName == selected
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    fun onIconSelected(id: String) {
        manager.setSelectedAliasName(id)
    }

    fun setOn(on: Boolean) {
        manager.setOn(on)
    }

    data class IconAndName(
        val id: String,
        @DrawableRes val icon: Int,
        @StringRes val name: Int,
        val selected: Boolean,
    )
}
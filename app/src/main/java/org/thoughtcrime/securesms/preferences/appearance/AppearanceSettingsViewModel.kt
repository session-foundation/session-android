package org.thoughtcrime.securesms.preferences.appearance

import androidx.annotation.StyleRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.reviews.InAppReviewManager
import org.thoughtcrime.securesms.ui.theme.invalidateComposeThemeColors
import org.thoughtcrime.securesms.util.ThemeState
import org.thoughtcrime.securesms.util.themeState
import javax.inject.Inject

@HiltViewModel
class AppearanceSettingsViewModel @Inject constructor(
    private val prefs: TextSecurePreferences,
    private val inAppReviewManager: InAppReviewManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(prefs.themeState())
    val uiState: StateFlow<ThemeState> = _uiState

    fun setNewAccent(@StyleRes newAccentColorStyle: Int) {
        prefs.setAccentColorStyle(newAccentColorStyle)
        // update UI state
        _uiState.value = prefs.themeState()

        invalidateComposeThemeColors()

        viewModelScope.launch {
            inAppReviewManager.onEvent(InAppReviewManager.Event.ThemeChanged)
        }
    }

    fun setNewStyle(newThemeStyle: String) {
        prefs.setThemeStyle(newThemeStyle)
        // update UI state
        _uiState.value = prefs.themeState()

        invalidateComposeThemeColors()

        viewModelScope.launch {
            inAppReviewManager.onEvent(InAppReviewManager.Event.ThemeChanged)
        }
    }

    fun setNewFollowSystemSettings(followSystemSettings: Boolean) {
        prefs.setFollowSystemSettings(followSystemSettings)
        _uiState.value = prefs.themeState()

        invalidateComposeThemeColors()
    }
}

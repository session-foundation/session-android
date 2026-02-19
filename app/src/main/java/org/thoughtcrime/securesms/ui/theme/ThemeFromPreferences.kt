package org.thoughtcrime.securesms.ui.theme

import androidx.compose.ui.graphics.Color
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.BLUE_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.ORANGE_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.PINK_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.PURPLE_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.RED_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.YELLOW_ACCENT
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.preferences.ThemingPreferences


/**
 * Returns the compose theme based on saved preferences
 * Some behaviour is hardcoded to cater for legacy usage of people with themes already set
 * But future themes will be picked and set directly from the "Appearance" screen
 */
fun getColorsProvider(textSecurePreferences: TextSecurePreferences, preferenceStorage: PreferenceStorage): ThemeColorsProvider {
    val selectedTheme = textSecurePreferences.getThemeStyle()

    // get the chosen accent color from the preferences
    val selectedAccent = accentColor(preferenceStorage)

    val isOcean = "ocean" in selectedTheme

    val createLight = if (isOcean) ::OceanLight else ::ClassicLight
    val createDark = if (isOcean) ::OceanDark else ::ClassicDark

    return when {
        textSecurePreferences.getFollowSystemSettings() -> FollowSystemThemeColorsProvider(
            light = createLight(selectedAccent),
            dark = createDark(selectedAccent)
        )
        "light" in selectedTheme -> ThemeColorsProvider(createLight(selectedAccent))
        else -> ThemeColorsProvider(createDark(selectedAccent))
    }
}

fun accentColor(preferenceStorage: PreferenceStorage): Color = when(preferenceStorage[ThemingPreferences.SELECTED_ACCENT_COLOR]) {
    BLUE_ACCENT -> primaryBlue
    PURPLE_ACCENT -> primaryPurple
    PINK_ACCENT -> primaryPink
    RED_ACCENT -> primaryRed
    ORANGE_ACCENT -> primaryOrange
    YELLOW_ACCENT -> primaryYellow
    else -> primaryGreen
}

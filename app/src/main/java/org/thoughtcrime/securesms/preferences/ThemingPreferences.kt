package org.thoughtcrime.securesms.preferences

object ThemingPreferences {
    const val CLASSIC_DARK = "classic.dark"
    const val CLASSIC_LIGHT = "classic.light"
    const val OCEAN_DARK = "ocean.dark"
    const val OCEAN_LIGHT = "ocean.light"

    val SELECTED_STYLE = PreferenceKey.string("pref_selected_style", CLASSIC_DARK)
    val FOLLOW_SYSTEM_SETTINGS = PreferenceKey.boolean("pref_follow_system", false)
    val SELECTED_ACCENT_COLOR = PreferenceKey.string("selected_accent_color", null)
    val SELECTED_ACTIVITY_ALIAS_NAME = PreferenceKey.string("selected_activity_alias_name", null)
}

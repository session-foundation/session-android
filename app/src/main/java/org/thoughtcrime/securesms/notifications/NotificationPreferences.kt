package org.thoughtcrime.securesms.notifications

import android.provider.Settings
import org.thoughtcrime.securesms.preferences.PreferenceKey

object NotificationPreferences {
    val ENABLE: PreferenceKey<Boolean> = PreferenceKey.boolean("pref_key_enable_notifications", defaultValue = true)
    val RINGTONE: PreferenceKey<String?> = PreferenceKey.string(
        name = "pref_key_ringtone",
        defaultValue = Settings.System.DEFAULT_NOTIFICATION_URI.toString()
    )

    val ENABLE_VIBRATION: PreferenceKey<Boolean> = PreferenceKey.boolean("pref_key_vibrate", defaultValue = true)

    val PRIVACY: PreferenceKey<String?> = PreferenceKey.string("pref_key_notification_privacy", defaultValue = "all")

    val LED_COLOR: PreferenceKey<Int> = PreferenceKey.integer("pref_led_color_primary", 0)
}

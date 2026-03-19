package org.thoughtcrime.securesms.notifications

import network.loki.messenger.BuildConfig
import org.thoughtcrime.securesms.preferences.PreferenceKey

object NotificationPreferences {
    val PRIVACY: PreferenceKey<NotificationPrivacy> = PreferenceKey.enum(
        name = "notification.privacy",
        defaultValue = NotificationPrivacy.ShowNameAndContent
    )

    val SOUND_WHEN_APP_OPEN: PreferenceKey<Boolean> = PreferenceKey.boolean("pref_sound_when_app_open", true)
    val CHECKED_DOZE_WHITELIST: PreferenceKey<Boolean> = PreferenceKey.boolean("has_checked_doze_whitelist")
    val PUSH_ENABLED: PreferenceKey<Boolean> = PreferenceKey.boolean("pref_is_using_fcm${BuildConfig.PUSH_KEY_SUFFIX}")
}

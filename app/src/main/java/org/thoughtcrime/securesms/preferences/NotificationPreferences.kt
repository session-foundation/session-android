package org.thoughtcrime.securesms.preferences

object NotificationPreferences {
    @JvmField val NOTIFICATIONS_ENABLED = PreferenceKey.boolean("pref_key_enable_notifications", true)
    @JvmField val VIBRATE_ENABLED = PreferenceKey.boolean("pref_key_vibrate", true)
    @JvmField val RINGTONE = PreferenceKey.string("pref_key_ringtone", "content://settings/system/notification_sound")
    @JvmField val LED_COLOR = PreferenceKey.int("pref_led_color_primary", 0)
    @JvmField val CALL_NOTIFICATIONS_ENABLED = PreferenceKey.boolean("pref_call_notifications_enabled", false)
    @JvmField val NOTIFICATION_PRIVACY = PreferenceKey.string("pref_notification_privacy", "all")
    @JvmField val SOUND_WHEN_OPEN = PreferenceKey.boolean("pref_sound_when_app_open", false)
    @JvmField val REPEAT_ALERTS_COUNT = PreferenceKey.string("pref_repeat_alerts", "0")
    @JvmField val IN_THREAD_NOTIFICATIONS = PreferenceKey.boolean("pref_key_inthread_notifications", true)
    @JvmField val CHANNEL_VERSION = PreferenceKey.int("pref_notification_channel_version", 1)
    @JvmField val MESSAGES_CHANNEL_VERSION = PreferenceKey.int("pref_notification_messages_channel_version", 1)
    @JvmField val HAVE_SHOWN_TOKEN_PAGE_NOTIFICATION = PreferenceKey.boolean("pref_shown_a_notification_about_token_page", false)
}

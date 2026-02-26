package org.thoughtcrime.securesms.preferences.compose

import network.loki.messenger.BuildConfig
import org.thoughtcrime.securesms.preferences.PreferenceKey

object PrivacyPreferenceKeys {
    val CALL_NOTIFICATIONS_ENABLED = PreferenceKey.boolean("pref_call_notifications_enabled", false)
    val SCREEN_LOCK = PreferenceKey.boolean("pref_android_screen_lock", false)
    val DISABLE_PASSPHRASE = PreferenceKey.boolean("pref_disable_passphrase", true)
    val READ_RECEIPTS = PreferenceKey.boolean("pref_read_receipts", false)
    val TYPING_INDICATORS = PreferenceKey.boolean("pref_typing_indicators", false)
    val LINK_PREVIEWS = PreferenceKey.boolean("pref_link_previews", false)
    val INCOGNITO_KEYBOARD = PreferenceKey.boolean("pref_incognito_keyboard", true)
}

object NotificationsPreferenceKeys {
    val PUSH_ENABLED = PreferenceKey.boolean("pref_is_using_fcm${BuildConfig.PUSH_KEY_SUFFIX}", false)
    val HAS_CHECKED_DOZE_WHITELIST = PreferenceKey.boolean("has_checked_doze_whitelist", false)
    val RINGTONE = PreferenceKey.string("pref_key_ringtone")
    val SOUND_WHEN_OPEN = PreferenceKey.boolean("pref_sound_when_app_open", false)
    val VIBRATE = PreferenceKey.boolean("pref_key_vibrate", true)
    val NOTIFICATION_PRIVACY = PreferenceKey.string("pref_notification_privacy", "all")
}

object ChatsPreferenceKeys {
    val THREAD_TRIM_ENABLED = PreferenceKey.boolean("pref_trim_threads", true)
    val SEND_WITH_ENTER = PreferenceKey.boolean("pref_enter_sends", false)
    val AUTOPLAY_AUDIO_MESSAGES = PreferenceKey.boolean("pref_autoplay_audio", false)
}

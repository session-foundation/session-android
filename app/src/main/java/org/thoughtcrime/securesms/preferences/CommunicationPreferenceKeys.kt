package org.thoughtcrime.securesms.preferences

import network.loki.messenger.BuildConfig

object CommunicationPreferenceKeys {
    val READ_RECEIPTS = PreferenceKey.boolean("pref_read_receipts", false)
    val TYPING_INDICATORS = PreferenceKey.boolean("pref_typing_indicators", false)
    val CALL_NOTIFICATIONS_ENABLED = PreferenceKey.boolean("pref_call_notifications_enabled", false)
    val FORCE_POST_PRO = PreferenceKey.boolean("pref_force_post_pro", false)
    val PUSH_ENABLED = PreferenceKey.boolean("pref_is_using_fcm${BuildConfig.PUSH_KEY_SUFFIX}", false)
}

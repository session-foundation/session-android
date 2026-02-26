package org.thoughtcrime.securesms.home

import network.loki.messenger.BuildConfig
import org.thoughtcrime.securesms.preferences.PreferenceKey

object HomePreferenceKeys {
    val HAS_HIDDEN_MESSAGE_REQUESTS = PreferenceKey.boolean("pref_message_requests_hidden", false)
    val HAS_CHECKED_DOZE_WHITELIST = PreferenceKey.boolean("has_checked_doze_whitelist", false)
    val PUSH_ENABLED = PreferenceKey.boolean("pref_is_using_fcm${BuildConfig.PUSH_KEY_SUFFIX}", false)
    val HAS_SEEN_PRO_EXPIRING = PreferenceKey.boolean("has_seen_pro_expiring", false)
    val HAS_SEEN_PRO_EXPIRED = PreferenceKey.boolean("has_seen_pro_expired", false)
    val HAS_RECEIVED_LEGACY_CONFIG = PreferenceKey.boolean("has_received_legacy_config", false)
}

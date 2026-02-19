package org.thoughtcrime.securesms.preferences

import network.loki.messenger.BuildConfig

object PushPreferences {
    val IS_PUSH_ENABLED = PreferenceKey.boolean("pref_is_using_fcm${BuildConfig.PUSH_KEY_SUFFIX}", false)
}

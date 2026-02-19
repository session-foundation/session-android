package org.thoughtcrime.securesms.preferences

import org.thoughtcrime.securesms.preferences.PreferenceKey

object PushPreferences {
    fun isPushEnabled(pushSuffix: String) = PreferenceKey.boolean("pref_is_using_fcm$pushSuffix", false)
}

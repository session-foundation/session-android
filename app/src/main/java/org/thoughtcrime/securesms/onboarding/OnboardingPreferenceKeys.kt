package org.thoughtcrime.securesms.onboarding

import network.loki.messenger.BuildConfig
import org.thoughtcrime.securesms.preferences.PreferenceKey

object OnboardingPreferenceKeys {
    val PUSH_ENABLED = PreferenceKey.boolean("pref_is_using_fcm${BuildConfig.PUSH_KEY_SUFFIX}", false)
    val CONFIGURATION_SYNCED = PreferenceKey.boolean("pref_configuration_synced", false)
    val PASSWORD_DISABLED = PreferenceKey.boolean("pref_disable_passphrase", true)
}

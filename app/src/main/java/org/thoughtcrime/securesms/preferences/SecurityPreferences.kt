package org.thoughtcrime.securesms.preferences

object SecurityPreferences {
    @JvmField val SCREEN_LOCK = PreferenceKey.boolean("pref_android_screen_lock", false)
    @JvmField val PASSWORD_DISABLED = PreferenceKey.boolean("pref_disable_passphrase", true)
    @JvmField val SCREEN_LOCK_TIMEOUT = PreferenceKey.long("pref_android_screen_lock_timeout", 0L)
    @JvmField val PASSPHRASE_TIMEOUT_ENABLED = PreferenceKey.boolean("pref_timeout_passphrase", false)
    @JvmField val PASSPHRASE_TIMEOUT_INTERVAL = PreferenceKey.int("pref_timeout_interval", 5 * 60)
    @JvmField val NEEDS_SQLCIPHER_MIGRATION = PreferenceKey.boolean("pref_needs_sql_cipher_migration", false)
}

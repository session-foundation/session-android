package org.thoughtcrime.securesms.preferences

object BackupPreferences {
    val BACKUP_ENABLED = PreferenceKey.boolean("pref_backup_enabled_v3", false)
    val BACKUP_PASSPHRASE = PreferenceKey.string("pref_backup_passphrase", null)
    val ENCRYPTED_BACKUP_PASSPHRASE = PreferenceKey.string("pref_encrypted_backup_passphrase", null)
    val NEXT_BACKUP_TIME = PreferenceKey.long("pref_backup_next_time", -1L)
    val BACKUP_SAVE_DIR = PreferenceKey.string("pref_save_dir", null)
}

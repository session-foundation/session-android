package org.session.libsession.utilities

import android.content.Context
import android.hardware.Camera
import android.net.Uri
import android.provider.Settings
import androidx.annotation.ArrayRes
import androidx.annotation.StyleRes
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager.getDefaultSharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.session.libsession.R
import org.session.libsession.utilities.TextSecurePreferences.Companion.ATTACHMENT_ENCRYPTED_SECRET
import org.session.libsession.utilities.TextSecurePreferences.Companion.ATTACHMENT_UNENCRYPTED_SECRET
import org.session.libsession.utilities.TextSecurePreferences.Companion.AUTOPLAY_AUDIO_MESSAGES
import org.session.libsession.utilities.TextSecurePreferences.Companion.BACKUP_ENABLED
import org.session.libsession.utilities.TextSecurePreferences.Companion.BACKUP_PASSPHRASE
import org.session.libsession.utilities.TextSecurePreferences.Companion.BACKUP_SAVE_DIR
import org.session.libsession.utilities.TextSecurePreferences.Companion.BACKUP_TIME
import org.session.libsession.utilities.TextSecurePreferences.Companion.CALL_NOTIFICATIONS_ENABLED
import org.session.libsession.utilities.TextSecurePreferences.Companion.CLASSIC_DARK
import org.session.libsession.utilities.TextSecurePreferences.Companion.CLASSIC_LIGHT
import org.session.libsession.utilities.TextSecurePreferences.Companion.DATABASE_ENCRYPTED_SECRET
import org.session.libsession.utilities.TextSecurePreferences.Companion.DATABASE_UNENCRYPTED_SECRET
import org.session.libsession.utilities.TextSecurePreferences.Companion.DIRECT_CAPTURE_CAMERA_ID
import org.session.libsession.utilities.TextSecurePreferences.Companion.ENCRYPTED_BACKUP_PASSPHRASE
import org.session.libsession.utilities.TextSecurePreferences.Companion.ENVIRONMENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.FOLLOW_SYSTEM_SETTINGS
import org.session.libsession.utilities.TextSecurePreferences.Companion.GIF_GRID_LAYOUT
import org.session.libsession.utilities.TextSecurePreferences.Companion.GIF_METADATA_WARNING
import org.session.libsession.utilities.TextSecurePreferences.Companion.HAS_HIDDEN_MESSAGE_REQUESTS
import org.session.libsession.utilities.TextSecurePreferences.Companion.HAS_HIDDEN_NOTE_TO_SELF
import org.session.libsession.utilities.TextSecurePreferences.Companion.HIDE_PASSWORD
import org.session.libsession.utilities.TextSecurePreferences.Companion.INCOGNITO_KEYBORAD_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.IS_PUSH_ENABLED
import org.session.libsession.utilities.TextSecurePreferences.Companion.LAST_VACUUM_TIME
import org.session.libsession.utilities.TextSecurePreferences.Companion.LAST_VERSION_CHECK
import org.session.libsession.utilities.TextSecurePreferences.Companion.LEGACY_PREF_KEY_SELECTED_UI_MODE
import org.session.libsession.utilities.TextSecurePreferences.Companion.LINK_PREVIEWS
import org.session.libsession.utilities.TextSecurePreferences.Companion.NEEDS_SQLCIPHER_MIGRATION
import org.session.libsession.utilities.TextSecurePreferences.Companion.NOTIFICATION_PRIORITY_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.NOTIFICATION_PRIVACY_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.OCEAN_DARK
import org.session.libsession.utilities.TextSecurePreferences.Companion.OCEAN_LIGHT
import org.session.libsession.utilities.TextSecurePreferences.Companion.PROFILE_AVATAR_ID_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.PROFILE_AVATAR_URL_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.PROFILE_KEY_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.PROFILE_NAME_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.READ_RECEIPTS_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.SCREEN_LOCK
import org.session.libsession.utilities.TextSecurePreferences.Companion.SCREEN_LOCK_TIMEOUT
import org.session.libsession.utilities.TextSecurePreferences.Companion.SELECTED_ACCENT_COLOR
import org.session.libsession.utilities.TextSecurePreferences.Companion.SELECTED_STYLE
import org.session.libsession.utilities.TextSecurePreferences.Companion.SHOWN_CALL_NOTIFICATION
import org.session.libsession.utilities.TextSecurePreferences.Companion.SHOWN_CALL_WARNING
import org.session.libsession.utilities.TextSecurePreferences.Companion.TYPING_INDICATORS
import org.session.libsession.utilities.TextSecurePreferences.Companion._events
import org.session.libsignal.utilities.Log

interface TextSecurePreferences {

    fun getConfigurationMessageSynced(): Boolean
    fun setConfigurationMessageSynced(value: Boolean)
    fun isPushEnabled(): Boolean
    fun setPushEnabled(value: Boolean)
    fun isScreenLockEnabled(): Boolean
    fun setScreenLockEnabled(value: Boolean)
    fun getScreenLockTimeout(): Long
    fun setScreenLockTimeout(value: Long)
    fun setBackupPassphrase(passphrase: String?)
    fun getBackupPassphrase(): String?
    fun setEncryptedBackupPassphrase(encryptedPassphrase: String?)
    fun getEncryptedBackupPassphrase(): String?
    fun isBackupEnabled(): Boolean
    fun setBackupEnabled(value: Boolean)
    fun getNextBackupTime(): Long
    fun setNextBackupTime(time: Long)
    fun getBackupSaveDir(): String?
    fun setBackupSaveDir(dirUri: String?)
    fun getNeedsSqlCipherMigration(): Boolean
    fun setAttachmentEncryptedSecret(secret: String)
    fun setAttachmentUnencryptedSecret(secret: String?)
    fun getAttachmentEncryptedSecret(): String?
    fun getAttachmentUnencryptedSecret(): String?
    fun setDatabaseEncryptedSecret(secret: String)
    fun setDatabaseUnencryptedSecret(secret: String?)
    fun getDatabaseUnencryptedSecret(): String?
    fun getDatabaseEncryptedSecret(): String?
    fun isIncognitoKeyboardEnabled(): Boolean
    fun isReadReceiptsEnabled(): Boolean
    fun setReadReceiptsEnabled(enabled: Boolean)
    fun isTypingIndicatorsEnabled(): Boolean
    fun setTypingIndicatorsEnabled(enabled: Boolean)
    fun isLinkPreviewsEnabled(): Boolean
    fun setLinkPreviewsEnabled(enabled: Boolean)
    fun hasSeenGIFMetaDataWarning(): Boolean
    fun setHasSeenGIFMetaDataWarning()
    fun isGifSearchInGridLayout(): Boolean
    fun setIsGifSearchInGridLayout(isGrid: Boolean)
    fun getProfileKey(): String?
    fun setProfileKey(key: String?)
    fun setProfileName(name: String?)
    fun getProfileName(): String?
    fun setProfileAvatarId(id: Int)
    fun getProfileAvatarId(): Int
    fun setProfilePictureURL(url: String?)
    fun getProfilePictureURL(): String?
    fun getNotificationPriority(): Int
    fun getMessageBodyTextSize(): Int
    fun setDirectCaptureCameraId(value: Int)
    fun getDirectCaptureCameraId(): Int
    fun getNotificationPrivacy(): NotificationPrivacyPreference
    fun getRepeatAlertsCount(): Int
    fun getLocalRegistrationId(): Int
    fun setLocalRegistrationId(registrationId: Int)
    fun isInThreadNotifications(): Boolean
    fun isUniversalUnidentifiedAccess(): Boolean
    fun getUpdateApkRefreshTime(): Long
    fun setUpdateApkRefreshTime(value: Long)
    fun setUpdateApkDownloadId(value: Long)
    fun getUpdateApkDownloadId(): Long
    fun setUpdateApkDigest(value: String?)
    fun getUpdateApkDigest(): String?
    fun getLocalNumber(): String?
    fun watchLocalNumber(): StateFlow<String?>
    fun getHasLegacyConfig(): Boolean
    fun setHasLegacyConfig(newValue: Boolean)
    fun setLocalNumber(localNumber: String)
    fun removeLocalNumber()
    fun isEnterSendsEnabled(): Boolean
    fun isPasswordDisabled(): Boolean
    fun setPasswordDisabled(disabled: Boolean)
    fun getLastVersionCode(): Int
    fun setLastVersionCode(versionCode: Int)
    fun isPassphraseTimeoutEnabled(): Boolean
    fun getPassphraseTimeoutInterval(): Int
    fun getLanguage(): String?
    fun isNotificationsEnabled(): Boolean
    fun getNotificationRingtone(): Uri
    fun removeNotificationRingtone()
    fun setNotificationRingtone(ringtone: String?)
    fun setNotificationVibrateEnabled(enabled: Boolean)
    fun isNotificationVibrateEnabled(): Boolean
    fun getNotificationLedColor(): Int
    fun isThreadLengthTrimmingEnabled(): Boolean
    fun getMobileMediaDownloadAllowed(): Set<String>?
    fun getWifiMediaDownloadAllowed(): Set<String>?
    fun getRoamingMediaDownloadAllowed(): Set<String>?
    fun getMediaDownloadAllowed(key: String, @ArrayRes defaultValuesRes: Int): Set<String>?
    fun getLogEncryptedSecret(): String?
    fun setLogEncryptedSecret(base64Secret: String?)
    fun getLogUnencryptedSecret(): String?
    fun setLogUnencryptedSecret(base64Secret: String?)
    fun getNotificationChannelVersion(): Int
    fun setNotificationChannelVersion(version: Int)
    fun getNotificationMessagesChannelVersion(): Int
    fun setNotificationMessagesChannelVersion(version: Int)
    fun getBooleanPreference(key: String?, defaultValue: Boolean): Boolean
    fun setBooleanPreference(key: String?, value: Boolean)
    fun getStringPreference(key: String, defaultValue: String?): String?
    fun setStringPreference(key: String?, value: String?)
    fun getIntegerPreference(key: String, defaultValue: Int): Int
    fun setIntegerPreference(key: String, value: Int)
    fun setIntegerPreferenceBlocking(key: String, value: Int): Boolean
    fun getLongPreference(key: String, defaultValue: Long): Long
    fun setLongPreference(key: String, value: Long)
    fun removePreference(key: String)
    fun getStringSetPreference(key: String, defaultValues: Set<String>): Set<String>?
    fun getHasViewedSeed(): Boolean
    fun setHasViewedSeed(hasViewedSeed: Boolean)
    fun setRestorationTime(time: Long)
    fun getRestorationTime(): Long
    fun getLastProfilePictureUpload(): Long
    fun setLastProfilePictureUpload(newValue: Long)
    fun getLastSnodePoolRefreshDate(): Long
    fun setLastSnodePoolRefreshDate(date: Date)
    fun shouldUpdateProfile(profileUpdateTime: Long): Boolean
    fun setLastProfileUpdateTime(profileUpdateTime: Long)
    fun getLastOpenTimeDate(): Long
    fun setLastOpenDate()
    fun hasSeenLinkPreviewSuggestionDialog(): Boolean
    fun setHasSeenLinkPreviewSuggestionDialog()
    fun hasHiddenMessageRequests(): Boolean
    fun setHasHiddenMessageRequests(hidden: Boolean)
    fun hasHiddenNoteToSelf(): Boolean
    fun setHasHiddenNoteToSelf(hidden: Boolean)
    fun setShownCallWarning(): Boolean
    fun setShownCallNotification(): Boolean
    fun isCallNotificationsEnabled(): Boolean
    fun getLastVacuum(): Long
    fun setLastVacuumNow()
    fun getFingerprintKeyGenerated(): Boolean
    fun setFingerprintKeyGenerated()
    fun getSelectedAccentColor(): String?
    @StyleRes fun getAccentColorStyle(): Int?
    fun setAccentColorStyle(@StyleRes newColorStyle: Int?)
    fun getThemeStyle(): String
    fun getFollowSystemSettings(): Boolean
    fun setThemeStyle(themeStyle: String)
    fun setFollowSystemSettings(followSystemSettings: Boolean)
    fun autoplayAudioMessages(): Boolean
    fun hasForcedNewConfig(): Boolean
    fun hasPreference(key: String): Boolean
    fun clearAll()
    fun getHidePassword(): Boolean
    fun setHidePassword(value: Boolean)
    fun getLastVersionCheck(): Long
    fun setLastVersionCheck()
    fun getEnvironment(): Environment
    fun setEnvironment(value: Environment)

    var migratedToGroupV2Config: Boolean

    companion object {
        // Basic constants only (no static methods!)

        val TAG = TextSecurePreferences::class.simpleName

        // For flows or events
        internal val _events = MutableSharedFlow<String>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val events: SharedFlow<String> get() = _events.asSharedFlow()

        @JvmStatic
        val IS_PUSH_ENABLED: String
            get() = "pref_is_using_fcm$pushSuffix"

        @JvmStatic
        var pushSuffix = ""

        // Preference keys and other constants
        const val DISABLE_PASSPHRASE_PREF = "pref_disable_passphrase"
        const val LANGUAGE_PREF = "pref_language"
        const val THREAD_TRIM_NOW = "pref_trim_now"
        const val LAST_VERSION_CODE_PREF = "last_version_code"
        const val RINGTONE_PREF = "pref_key_ringtone"
        const val VIBRATE_PREF = "pref_key_vibrate"
        const val NOTIFICATION_PREF = "pref_key_enable_notifications"
        const val LED_COLOR_PREF = "pref_led_color"
        const val LED_COLOR_PREF_PRIMARY = "pref_led_color_primary"
        const val LED_BLINK_PREF = "pref_led_blink"
        const val LED_BLINK_PREF_CUSTOM = "pref_led_blink_custom"
        const val PASSPHRASE_TIMEOUT_INTERVAL_PREF = "pref_timeout_interval"
        const val PASSPHRASE_TIMEOUT_PREF = "pref_timeout_passphrase"
        const val ENTER_SENDS_PREF = "pref_enter_sends"
        const val THREAD_TRIM_ENABLED = "pref_trim_threads"
        internal const val LOCAL_NUMBER_PREF = "pref_local_number"
        const val REGISTERED_GCM_PREF = "pref_gcm_registered"
        const val UPDATE_APK_REFRESH_TIME_PREF = "pref_update_apk_refresh_time"
        const val UPDATE_APK_DOWNLOAD_ID = "pref_update_apk_download_id"
        const val UPDATE_APK_DIGEST = "pref_update_apk_digest"
        const val IN_THREAD_NOTIFICATION_PREF = "pref_key_inthread_notifications"
        const val IN_APP_NOTIFICATION_SOUNDS = "pref_sound_when_app_open"
        const val MESSAGE_BODY_TEXT_SIZE_PREF = "pref_message_body_text_size"
        const val LOCAL_REGISTRATION_ID_PREF = "pref_local_registration_id"
        const val REPEAT_ALERTS_PREF = "pref_repeat_alerts"
        const val NOTIFICATION_PRIVACY_PREF = "pref_notification_privacy"
        const val NOTIFICATION_PRIORITY_PREF = "pref_notification_priority"
        const val MEDIA_DOWNLOAD_MOBILE_PREF = "pref_media_download_mobile"
        const val MEDIA_DOWNLOAD_WIFI_PREF = "pref_media_download_wifi"
        const val MEDIA_DOWNLOAD_ROAMING_PREF = "pref_media_download_roaming"
        const val DIRECT_CAPTURE_CAMERA_ID = "pref_direct_capture_camera_id"
        const val PROFILE_KEY_PREF = "pref_profile_key"
        const val PROFILE_NAME_PREF = "pref_profile_name"
        const val PROFILE_AVATAR_ID_PREF = "pref_profile_avatar_id"
        const val PROFILE_AVATAR_URL_PREF = "pref_profile_avatar_url"
        const val READ_RECEIPTS_PREF = "pref_read_receipts"
        const val INCOGNITO_KEYBORAD_PREF = "pref_incognito_keyboard"
        const val DATABASE_ENCRYPTED_SECRET = "pref_database_encrypted_secret"
        const val DATABASE_UNENCRYPTED_SECRET = "pref_database_unencrypted_secret"
        const val ATTACHMENT_ENCRYPTED_SECRET = "pref_attachment_encrypted_secret"
        const val ATTACHMENT_UNENCRYPTED_SECRET = "pref_attachment_unencrypted_secret"
        const val NEEDS_SQLCIPHER_MIGRATION = "pref_needs_sql_cipher_migration"
        const val BACKUP_ENABLED = "pref_backup_enabled_v3"
        const val BACKUP_PASSPHRASE = "pref_backup_passphrase"
        const val ENCRYPTED_BACKUP_PASSPHRASE = "pref_encrypted_backup_passphrase"
        const val BACKUP_TIME = "pref_backup_next_time"
        const val BACKUP_NOW = "pref_backup_create"
        const val BACKUP_SAVE_DIR = "pref_save_dir"
        const val SCREEN_LOCK = "pref_android_screen_lock"
        const val SCREEN_LOCK_TIMEOUT = "pref_android_screen_lock_timeout"
        const val LOG_ENCRYPTED_SECRET = "pref_log_encrypted_secret"
        const val LOG_UNENCRYPTED_SECRET = "pref_log_unencrypted_secret"
        const val NOTIFICATION_CHANNEL_VERSION = "pref_notification_channel_version"
        const val NOTIFICATION_MESSAGES_CHANNEL_VERSION = "pref_notification_messages_channel_version"
        const val UNIVERSAL_UNIDENTIFIED_ACCESS = "pref_universal_unidentified_access"
        const val TYPING_INDICATORS = "pref_typing_indicators"
        const val LINK_PREVIEWS = "pref_link_previews"
        const val GIF_METADATA_WARNING = "has_seen_gif_metadata_warning"
        const val GIF_GRID_LAYOUT = "pref_gif_grid_layout"
        const val CONFIGURATION_SYNCED = "pref_configuration_synced"
        const val LAST_PROFILE_UPDATE_TIME = "pref_last_profile_update_time"
        const val LAST_OPEN_DATE = "pref_last_open_date"
        const val HAS_HIDDEN_MESSAGE_REQUESTS = "pref_message_requests_hidden"
        const val HAS_HIDDEN_NOTE_TO_SELF = "pref_note_to_self_hidden"
        const val CALL_NOTIFICATIONS_ENABLED = "pref_call_notifications_enabled"
        const val SHOWN_CALL_WARNING = "pref_shown_call_warning"
        const val SHOWN_CALL_NOTIFICATION = "pref_shown_call_notification"
        const val LAST_VACUUM_TIME = "pref_last_vacuum_time"
        const val AUTOPLAY_AUDIO_MESSAGES = "pref_autoplay_audio"
        const val FINGERPRINT_KEY_GENERATED = "fingerprint_key_generated"
        const val SELECTED_ACCENT_COLOR = "selected_accent_color"
        const val LAST_VERSION_CHECK = "pref_last_version_check"
        const val ENVIRONMENT = "debug_environment"
        const val MIGRATED_TO_GROUP_V2_CONFIG = "migrated_to_group_v2_config"
        const val HAS_RECEIVED_LEGACY_CONFIG = "has_received_legacy_config"
        const val HAS_FORCED_NEW_CONFIG = "has_forced_new_config"

        // Possible accent color constants
        const val GREEN_ACCENT = "accent_green"
        const val BLUE_ACCENT = "accent_blue"
        const val PURPLE_ACCENT = "accent_purple"
        const val PINK_ACCENT = "accent_pink"
        const val RED_ACCENT = "accent_red"
        const val ORANGE_ACCENT = "accent_orange"
        const val YELLOW_ACCENT = "accent_yellow"

        // Theme/style constants
        const val SELECTED_STYLE = "pref_selected_style"        // classic_dark/light, ocean_dark/light
        const val FOLLOW_SYSTEM_SETTINGS = "pref_follow_system" // follow system day/night
        const val HIDE_PASSWORD = "pref_hide_password"
        const val LEGACY_PREF_KEY_SELECTED_UI_MODE = "SELECTED_UI_MODE" // used for migration
        const val CLASSIC_DARK = "classic.dark"
        const val CLASSIC_LIGHT = "classic.light"
        const val OCEAN_DARK = "ocean.dark"
        const val OCEAN_LIGHT = "ocean.light"

        // Key name for if we've warned the user that saving attachments will allow other apps to access them.
        const val ALLOW_MESSAGE_REQUESTS = "libsession.ALLOW_MESSAGE_REQUESTS"
        const val HAVE_WARNED_USER_ABOUT_SAVING_ATTACHMENTS = "libsession.HAVE_WARNED_USER_ABOUT_SAVING_ATTACHMENTS"

        // This one's a nuisance. It's used in `OpenGroupApi.timeSinceLastOpen by lazy` so I'm just going to put it
        // back for the time being. -ACL 2025/01/30
        @JvmStatic
        fun getLastOpenTimeDate(context: Context): Long {
            return getDefaultSharedPreferences(context).getLong(LAST_OPEN_DATE, 0)
        }
    }
}

@Singleton
class AppTextSecurePreferences @Inject constructor(
    @ApplicationContext private val context: Context
): TextSecurePreferences {

    // Keep a reference to SharedPreferences
    private val sharedPreferences = getDefaultSharedPreferences(context)

//    /**
//     * Utility functions to ensure I/O happens on Dispatchers.IO
//     * while the call remains synchronous from the caller's perspective.
//     */
//    private inline fun <T> readPref(block: SharedPreferences.() -> T): T {
//        return runBlocking(Dispatchers.IO) {
//            sharedPreferences.block()
//        }
//    }
//
//    private inline fun writePref(block: SharedPreferences.Editor.() -> Unit) {
//        runBlocking(Dispatchers.IO) {
//            sharedPreferences.edit().apply {
//                block()
//                apply()
//            }
//        }
//    }
//
//    /**
//     * For methods that must return a boolean success (i.e., .commit()),
//     * you can do it like this:
//     */
//    private inline fun commitPref(block: SharedPreferences.Editor.() -> Unit): Boolean {
//        return runBlocking(Dispatchers.IO) {
//            sharedPreferences.edit().apply {
//                block()
//                commit()
//            }
//        }
//    }


    private val localNumberState = MutableStateFlow(getStringPreference(TextSecurePreferences.LOCAL_NUMBER_PREF, null))

    override var migratedToGroupV2Config: Boolean
        get() = getBooleanPreference(TextSecurePreferences.MIGRATED_TO_GROUP_V2_CONFIG, false)
        set(value) = setBooleanPreference(TextSecurePreferences.MIGRATED_TO_GROUP_V2_CONFIG, value)

    override fun getConfigurationMessageSynced(): Boolean = getBooleanPreference(TextSecurePreferences.CONFIGURATION_SYNCED, false)

    override fun setConfigurationMessageSynced(value: Boolean) {
        setBooleanPreference(TextSecurePreferences.CONFIGURATION_SYNCED, value)
        _events.tryEmit(TextSecurePreferences.CONFIGURATION_SYNCED)
    }

    override fun isPushEnabled() = getBooleanPreference(IS_PUSH_ENABLED, false)
    override fun setPushEnabled(value: Boolean) = setBooleanPreference(IS_PUSH_ENABLED, value)

    override fun isScreenLockEnabled(): Boolean = getBooleanPreference(SCREEN_LOCK, false)
    override fun setScreenLockEnabled(value: Boolean) = setBooleanPreference(SCREEN_LOCK, value)

    override fun getScreenLockTimeout(): Long = getLongPreference(SCREEN_LOCK_TIMEOUT, 0)
    override fun setScreenLockTimeout(value: Long) = setLongPreference(SCREEN_LOCK_TIMEOUT, value)

    override fun setBackupPassphrase(passphrase: String?) = setStringPreference(BACKUP_PASSPHRASE, passphrase)
    override fun getBackupPassphrase(): String? = getStringPreference(BACKUP_PASSPHRASE, null)

    override fun setEncryptedBackupPassphrase(encryptedPassphrase: String?) = setStringPreference(ENCRYPTED_BACKUP_PASSPHRASE, encryptedPassphrase)
    override fun getEncryptedBackupPassphrase(): String? = getStringPreference(ENCRYPTED_BACKUP_PASSPHRASE, null)

    override fun isBackupEnabled(): Boolean = getBooleanPreference(BACKUP_ENABLED, false)
    override fun setBackupEnabled(value: Boolean) = setBooleanPreference(BACKUP_ENABLED, value)

    override fun getNextBackupTime(): Long = getLongPreference(BACKUP_TIME, -1)
    override fun setNextBackupTime(time: Long) = setLongPreference(BACKUP_TIME, time)

    override fun getBackupSaveDir(): String? = getStringPreference(BACKUP_SAVE_DIR, null)
    override fun setBackupSaveDir(dirUri: String?) = setStringPreference(BACKUP_SAVE_DIR, dirUri)

    override fun getNeedsSqlCipherMigration(): Boolean = getBooleanPreference(NEEDS_SQLCIPHER_MIGRATION, false)

    override fun getAttachmentEncryptedSecret(): String? = getStringPreference(ATTACHMENT_ENCRYPTED_SECRET, null)
    override fun setAttachmentEncryptedSecret(secret: String) = setStringPreference(ATTACHMENT_ENCRYPTED_SECRET, secret)

    override fun getAttachmentUnencryptedSecret(): String? = getStringPreference(ATTACHMENT_UNENCRYPTED_SECRET, null)
    override fun setAttachmentUnencryptedSecret(secret: String?) = setStringPreference(ATTACHMENT_UNENCRYPTED_SECRET, secret)

    override fun getDatabaseEncryptedSecret(): String? = getStringPreference(DATABASE_ENCRYPTED_SECRET, null)
    override fun setDatabaseEncryptedSecret(secret: String) = setStringPreference(DATABASE_ENCRYPTED_SECRET, secret)

    override fun getDatabaseUnencryptedSecret(): String? = getStringPreference(DATABASE_UNENCRYPTED_SECRET, null)
    override fun setDatabaseUnencryptedSecret(secret: String?) = setStringPreference(DATABASE_UNENCRYPTED_SECRET, secret)

    override fun isIncognitoKeyboardEnabled(): Boolean = getBooleanPreference(INCOGNITO_KEYBORAD_PREF, true)

    override fun isReadReceiptsEnabled(): Boolean = getBooleanPreference(READ_RECEIPTS_PREF, false)
    override fun setReadReceiptsEnabled(enabled: Boolean) = setBooleanPreference(READ_RECEIPTS_PREF, enabled)

    override fun isTypingIndicatorsEnabled(): Boolean = getBooleanPreference(TYPING_INDICATORS, false)
    override fun setTypingIndicatorsEnabled(enabled: Boolean) = setBooleanPreference(TYPING_INDICATORS, enabled)

    override fun isLinkPreviewsEnabled(): Boolean = getBooleanPreference(LINK_PREVIEWS, false)
    override fun setLinkPreviewsEnabled(enabled: Boolean) = setBooleanPreference(LINK_PREVIEWS, enabled)

    override fun hasSeenGIFMetaDataWarning(): Boolean = getBooleanPreference(GIF_METADATA_WARNING, false)
    override fun setHasSeenGIFMetaDataWarning() = setBooleanPreference(GIF_METADATA_WARNING, true)

    override fun isGifSearchInGridLayout(): Boolean = getBooleanPreference(GIF_GRID_LAYOUT, false)
    override fun setIsGifSearchInGridLayout(isGrid: Boolean) = setBooleanPreference(GIF_GRID_LAYOUT, isGrid)

    override fun getProfileKey(): String? = getStringPreference(PROFILE_KEY_PREF, null)
    override fun setProfileKey(key: String?) = setStringPreference(PROFILE_KEY_PREF, key)

    override fun setProfileName(name: String?) {
        setStringPreference(TextSecurePreferences.PROFILE_NAME_PREF, name)
        _events.tryEmit(TextSecurePreferences.PROFILE_NAME_PREF)
    }

    override fun getProfileName(): String? = getStringPreference(PROFILE_NAME_PREF, null)

    override fun getProfileAvatarId(): Int = getIntegerPreference(PROFILE_AVATAR_ID_PREF, 0)
    override fun setProfileAvatarId(id: Int) = setIntegerPreference(PROFILE_AVATAR_ID_PREF, id)

    override fun getProfilePictureURL(): String? = getStringPreference(PROFILE_AVATAR_URL_PREF, null)
    override fun setProfilePictureURL(url: String?) = setStringPreference(PROFILE_AVATAR_URL_PREF, url)

    override fun getNotificationPriority(): Int = getStringPreference(NOTIFICATION_PRIORITY_PREF, NotificationCompat.PRIORITY_HIGH.toString())!!.toInt()

    override fun getMessageBodyTextSize(): Int = getStringPreference(TextSecurePreferences.MESSAGE_BODY_TEXT_SIZE_PREF, "16")!!.toInt()

    override fun getDirectCaptureCameraId(): Int = getIntegerPreference(DIRECT_CAPTURE_CAMERA_ID, Camera.CameraInfo.CAMERA_FACING_BACK)
    override fun setDirectCaptureCameraId(value: Int) = setIntegerPreference(DIRECT_CAPTURE_CAMERA_ID, value)

    override fun getNotificationPrivacy(): NotificationPrivacyPreference = NotificationPrivacyPreference(getStringPreference(NOTIFICATION_PRIVACY_PREF, "all"))

    override fun getRepeatAlertsCount(): Int {
        return try {
            getStringPreference(TextSecurePreferences.REPEAT_ALERTS_PREF, "0")!!.toInt()
        } catch (e: NumberFormatException) {
            Log.w(TextSecurePreferences.TAG, e)
            0
        }
    }

    override fun getLocalRegistrationId(): Int {
        return getIntegerPreference(TextSecurePreferences.LOCAL_REGISTRATION_ID_PREF, 0)
    }

    override fun setLocalRegistrationId(registrationId: Int) {
        setIntegerPreference(TextSecurePreferences.LOCAL_REGISTRATION_ID_PREF, registrationId)
    }

    override fun isInThreadNotifications(): Boolean {
        return getBooleanPreference(TextSecurePreferences.IN_THREAD_NOTIFICATION_PREF, true)
    }

    override fun isUniversalUnidentifiedAccess(): Boolean {
        return getBooleanPreference(TextSecurePreferences.UNIVERSAL_UNIDENTIFIED_ACCESS, false)
    }

    override fun getUpdateApkRefreshTime(): Long {
        return getLongPreference(TextSecurePreferences.UPDATE_APK_REFRESH_TIME_PREF, 0L)
    }

    override fun setUpdateApkRefreshTime(value: Long) {
        setLongPreference(TextSecurePreferences.UPDATE_APK_REFRESH_TIME_PREF, value)
    }

    override fun setUpdateApkDownloadId(value: Long) {
        setLongPreference(TextSecurePreferences.UPDATE_APK_DOWNLOAD_ID, value)
    }

    override fun getUpdateApkDownloadId(): Long {
        return getLongPreference(TextSecurePreferences.UPDATE_APK_DOWNLOAD_ID, -1)
    }

    override fun setUpdateApkDigest(value: String?) {
        setStringPreference(TextSecurePreferences.UPDATE_APK_DIGEST, value)
    }

    override fun getUpdateApkDigest(): String? {
        return getStringPreference(TextSecurePreferences.UPDATE_APK_DIGEST, null)
    }

    override fun getLocalNumber(): String? {
        return localNumberState.value
    }

    override fun watchLocalNumber(): StateFlow<String?> {
        return localNumberState
    }

    override fun getHasLegacyConfig(): Boolean {
        return getBooleanPreference(TextSecurePreferences.HAS_RECEIVED_LEGACY_CONFIG, false)
    }

    override fun setHasLegacyConfig(newValue: Boolean) {
        setBooleanPreference(TextSecurePreferences.HAS_RECEIVED_LEGACY_CONFIG, newValue)
        TextSecurePreferences._events.tryEmit(TextSecurePreferences.HAS_RECEIVED_LEGACY_CONFIG)
    }

    override fun setLocalNumber(localNumber: String) {
        val normalised = localNumber.lowercase()
        setStringPreference(TextSecurePreferences.LOCAL_NUMBER_PREF, normalised)
        localNumberState.value = normalised
    }

    override fun removeLocalNumber() {
        localNumberState.value = null
        removePreference(TextSecurePreferences.LOCAL_NUMBER_PREF)
    }

    override fun isEnterSendsEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.ENTER_SENDS_PREF, false)
    }

    override fun isPasswordDisabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.DISABLE_PASSPHRASE_PREF, true)
    }

    override fun setPasswordDisabled(disabled: Boolean) {
        setBooleanPreference(TextSecurePreferences.DISABLE_PASSPHRASE_PREF, disabled)
    }

    override fun getLastVersionCode(): Int {
        return getIntegerPreference(TextSecurePreferences.LAST_VERSION_CODE_PREF, 0)
    }

    @Throws(IOException::class)
    override fun setLastVersionCode(versionCode: Int) {
        if (!setIntegerPreferenceBlocking(TextSecurePreferences.LAST_VERSION_CODE_PREF, versionCode)) {
            throw IOException("couldn't write version code to sharedpreferences")
        }
    }

    override fun isPassphraseTimeoutEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.PASSPHRASE_TIMEOUT_PREF, false)
    }

    override fun getPassphraseTimeoutInterval(): Int {
        return getIntegerPreference(TextSecurePreferences.PASSPHRASE_TIMEOUT_INTERVAL_PREF, 5 * 60)
    }

    override fun getLanguage(): String? {
        return getStringPreference(TextSecurePreferences.LANGUAGE_PREF, "zz")
    }

    override fun isNotificationsEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.NOTIFICATION_PREF, true)
    }

    override fun getNotificationRingtone(): Uri {
        var result = getStringPreference(TextSecurePreferences.RINGTONE_PREF, Settings.System.DEFAULT_NOTIFICATION_URI.toString())
        if (result != null && result.startsWith("file:")) {
            result = Settings.System.DEFAULT_NOTIFICATION_URI.toString()
        }
        return Uri.parse(result)
    }

    override fun removeNotificationRingtone() {
        removePreference(TextSecurePreferences.RINGTONE_PREF)
    }

    override fun setNotificationRingtone(ringtone: String?) {
        setStringPreference(TextSecurePreferences.RINGTONE_PREF, ringtone)
    }

    override fun setNotificationVibrateEnabled(enabled: Boolean) {
        setBooleanPreference(TextSecurePreferences.VIBRATE_PREF, enabled)
    }

    override fun isNotificationVibrateEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.VIBRATE_PREF, true)
    }

    override fun getNotificationLedColor(): Int {
        return getIntegerPreference(TextSecurePreferences.LED_COLOR_PREF_PRIMARY, context.getColor(R.color.accent_green))
    }

    override fun isThreadLengthTrimmingEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.THREAD_TRIM_ENABLED, true)
    }

    override fun getMobileMediaDownloadAllowed(): Set<String>? {
        return getMediaDownloadAllowed(TextSecurePreferences.MEDIA_DOWNLOAD_MOBILE_PREF, R.array.pref_media_download_mobile_data_default)
    }

    override fun getWifiMediaDownloadAllowed(): Set<String>? {
        return getMediaDownloadAllowed(TextSecurePreferences.MEDIA_DOWNLOAD_WIFI_PREF, R.array.pref_media_download_wifi_default)
    }

    override fun getRoamingMediaDownloadAllowed(): Set<String>? {
        return getMediaDownloadAllowed(TextSecurePreferences.MEDIA_DOWNLOAD_ROAMING_PREF, R.array.pref_media_download_roaming_default)
    }

    override fun getMediaDownloadAllowed(key: String, @ArrayRes defaultValuesRes: Int): Set<String>? {
        return getStringSetPreference(key, HashSet(listOf(*context.resources.getStringArray(defaultValuesRes))))
    }

    override fun getLogEncryptedSecret(): String? {
        return getStringPreference(TextSecurePreferences.LOG_ENCRYPTED_SECRET, null)
    }

    override fun setLogEncryptedSecret(base64Secret: String?) {
        setStringPreference(TextSecurePreferences.LOG_ENCRYPTED_SECRET, base64Secret)
    }

    override fun getLogUnencryptedSecret(): String? {
        return getStringPreference(TextSecurePreferences.LOG_UNENCRYPTED_SECRET, null)
    }

    override fun setLogUnencryptedSecret(base64Secret: String?) {
        setStringPreference(TextSecurePreferences.LOG_UNENCRYPTED_SECRET, base64Secret)
    }

    override fun getNotificationChannelVersion(): Int {
        return getIntegerPreference(TextSecurePreferences.NOTIFICATION_CHANNEL_VERSION, 1)
    }

    override fun setNotificationChannelVersion(version: Int) {
        setIntegerPreference(TextSecurePreferences.NOTIFICATION_CHANNEL_VERSION, version)
    }

    override fun getNotificationMessagesChannelVersion(): Int {
        return getIntegerPreference(TextSecurePreferences.NOTIFICATION_MESSAGES_CHANNEL_VERSION, 1)
    }

    override fun setNotificationMessagesChannelVersion(version: Int) {
        setIntegerPreference(TextSecurePreferences.NOTIFICATION_MESSAGES_CHANNEL_VERSION, version)
    }

    override fun hasForcedNewConfig(): Boolean =
        getBooleanPreference(TextSecurePreferences.HAS_FORCED_NEW_CONFIG, false)

    override fun getBooleanPreference(key: String?, defaultValue: Boolean): Boolean {
        //return sharedPreferences.getBoolean(key, defaultValue)
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.getBoolean(key, defaultValue)
        }
    }

    override fun setBooleanPreference(key: String?, value: Boolean) {
        //getDefaultSharedPreferences(context).edit().putBoolean(key, value).apply()
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.edit().putBoolean(key, value).apply()
        }
    }

    override fun getStringPreference(key: String, defaultValue: String?): String? {
        //return sharedPreferences.getString(key, defaultValue)
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.getString(key, defaultValue)
        }
    }

    override fun setStringPreference(key: String?, value: String?) {
        //getDefaultSharedPreferences(context).edit().putString(key, value).apply()
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.edit().putString(key, value).apply()
        }
    }

    override fun getIntegerPreference(key: String, defaultValue: Int): Int {
        //return sharedPreferences.getInt(key, defaultValue)
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.getInt(key, defaultValue)
        }
    }

    override fun setIntegerPreference(key: String, value: Int) {
        //getDefaultSharedPreferences(context).edit().putInt(key, value).apply()
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.edit().putInt(key, value).apply()
        }
    }

    override fun setIntegerPreferenceBlocking(key: String, value: Int): Boolean {
        //return sharedPreferences.edit().putInt(key, value).commit()
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.edit().putInt(key, value).commit()
        }
    }

    override fun getLongPreference(key: String, defaultValue: Long): Long {
        //return sharedPreferences.getLong(key, defaultValue)
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.getLong(key, defaultValue)
        }
    }

    override fun setLongPreference(key: String, value: Long) {
        //getDefaultSharedPreferences(context).edit().putLong(key, value).apply()
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.edit().putLong(key, value).apply()
        }
    }

    override fun hasPreference(key: String): Boolean {
        //return sharedPreferences.contains(key)
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.contains(key)
        }
    }

    override fun removePreference(key: String) {
        //getDefaultSharedPreferences(context).edit().remove(key).apply()
        return runBlocking(Dispatchers.IO) {
            sharedPreferences.edit().remove(key).apply()
        }
    }

    override fun getStringSetPreference(key: String, defaultValues: Set<String>): Set<String>? {
        /*
        val prefs = getDefaultSharedPreferences(context)
        return if (prefs.contains(key)) {
            prefs.getStringSet(key, emptySet())
        } else {
            defaultValues
        }
        */
        return runBlocking(Dispatchers.IO) {
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getStringSet(key, emptySet())
            } else {
                defaultValues
            }
        }
    }

    override fun getHasViewedSeed(): Boolean {
        return getBooleanPreference("has_viewed_seed", false)
    }

    override fun setHasViewedSeed(hasViewedSeed: Boolean) {
        setBooleanPreference("has_viewed_seed", hasViewedSeed)
    }

    override fun setRestorationTime(time: Long) {
        setLongPreference("restoration_time", time)
    }

    override fun getRestorationTime(): Long {
        return getLongPreference("restoration_time", 0)
    }

    override fun getLastProfilePictureUpload(): Long {
        return getLongPreference("last_profile_picture_upload", 0)
    }

    override fun setLastProfilePictureUpload(newValue: Long) {
        setLongPreference("last_profile_picture_upload", newValue)
    }

    override fun getLastSnodePoolRefreshDate(): Long {
        return getLongPreference("last_snode_pool_refresh_date", 0)
    }

    override fun setLastSnodePoolRefreshDate(date: Date) {
        setLongPreference("last_snode_pool_refresh_date", date.time)
    }

    override fun shouldUpdateProfile(profileUpdateTime: Long): Boolean {
        return profileUpdateTime > getLongPreference(TextSecurePreferences.LAST_PROFILE_UPDATE_TIME, 0)
    }

    override fun setLastProfileUpdateTime(profileUpdateTime: Long) {
        setLongPreference(TextSecurePreferences.LAST_PROFILE_UPDATE_TIME, profileUpdateTime)
    }

    override fun getLastOpenTimeDate(): Long {
        return getLongPreference(TextSecurePreferences.LAST_OPEN_DATE, 0)
    }

    override fun setLastOpenDate() {
        setLongPreference(TextSecurePreferences.LAST_OPEN_DATE, System.currentTimeMillis())
    }

    override fun hasSeenLinkPreviewSuggestionDialog(): Boolean {
        return getBooleanPreference("has_seen_link_preview_suggestion_dialog", false)
    }

    override fun setHasSeenLinkPreviewSuggestionDialog() {
        setBooleanPreference("has_seen_link_preview_suggestion_dialog", true)
    }

    override fun isCallNotificationsEnabled(): Boolean {
        return getBooleanPreference(CALL_NOTIFICATIONS_ENABLED, false)
    }

    override fun getLastVacuum(): Long {
        return getLongPreference(LAST_VACUUM_TIME, 0)
    }

    override fun setLastVacuumNow() {
        setLongPreference(LAST_VACUUM_TIME, System.currentTimeMillis())
    }

    override fun getLastVersionCheck(): Long {
        return getLongPreference(LAST_VERSION_CHECK, 0)
    }

    override fun setLastVersionCheck() {
        setLongPreference(LAST_VERSION_CHECK, System.currentTimeMillis())
    }

    override fun getEnvironment(): Environment {
        val environment = getStringPreference(ENVIRONMENT, null)
        return if (environment != null) {
            Environment.valueOf(environment)
        } else Environment.MAIN_NET
    }

    override fun setEnvironment(value: Environment) {
        setStringPreference(ENVIRONMENT, value.name)
    }

    override fun setShownCallNotification(): Boolean {
        val previousValue = getBooleanPreference(SHOWN_CALL_NOTIFICATION, false)
        if (previousValue) return false
        val setValue = true
        setBooleanPreference(SHOWN_CALL_NOTIFICATION, setValue)
        return previousValue != setValue
    }


    /**
     * Set the SHOWN_CALL_WARNING preference to `true`
     * Return `true` if the value did update (it was previously unset)
     */
    override fun setShownCallWarning() : Boolean {
        val previousValue = getBooleanPreference(SHOWN_CALL_WARNING, false)
        if (previousValue) {
            return false
        }
        val setValue = true
        setBooleanPreference(SHOWN_CALL_WARNING, setValue)
        return previousValue != setValue
    }

    override fun hasHiddenMessageRequests(): Boolean {
        return getBooleanPreference(HAS_HIDDEN_MESSAGE_REQUESTS, false)
    }

    override fun setHasHiddenMessageRequests(hidden: Boolean) {
        setBooleanPreference(HAS_HIDDEN_MESSAGE_REQUESTS, hidden)
        _events.tryEmit(HAS_HIDDEN_MESSAGE_REQUESTS)
    }

    override fun hasHiddenNoteToSelf(): Boolean {
        return getBooleanPreference(HAS_HIDDEN_NOTE_TO_SELF, false)
    }

    override fun setHasHiddenNoteToSelf(hidden: Boolean) {
        setBooleanPreference(HAS_HIDDEN_NOTE_TO_SELF, hidden)
        _events.tryEmit(HAS_HIDDEN_NOTE_TO_SELF)
    }

    override fun getFingerprintKeyGenerated(): Boolean {
        return getBooleanPreference(TextSecurePreferences.FINGERPRINT_KEY_GENERATED, false)
    }

    override fun setFingerprintKeyGenerated() {
        setBooleanPreference(TextSecurePreferences.FINGERPRINT_KEY_GENERATED, true)
    }

    override fun getSelectedAccentColor(): String? =
        getStringPreference(SELECTED_ACCENT_COLOR, null)

    @StyleRes
    override fun getAccentColorStyle(): Int? {
        return when (getSelectedAccentColor()) {
            TextSecurePreferences.GREEN_ACCENT -> R.style.PrimaryGreen
            TextSecurePreferences.BLUE_ACCENT -> R.style.PrimaryBlue
            TextSecurePreferences.PURPLE_ACCENT -> R.style.PrimaryPurple
            TextSecurePreferences.PINK_ACCENT -> R.style.PrimaryPink
            TextSecurePreferences.RED_ACCENT -> R.style.PrimaryRed
            TextSecurePreferences.ORANGE_ACCENT -> R.style.PrimaryOrange
            TextSecurePreferences.YELLOW_ACCENT -> R.style.PrimaryYellow
            else -> null
        }
    }

    override fun setAccentColorStyle(@StyleRes newColorStyle: Int?) {
        setStringPreference(
            TextSecurePreferences.SELECTED_ACCENT_COLOR, when (newColorStyle) {
                R.style.PrimaryGreen -> TextSecurePreferences.GREEN_ACCENT
                R.style.PrimaryBlue -> TextSecurePreferences.BLUE_ACCENT
                R.style.PrimaryPurple -> TextSecurePreferences.PURPLE_ACCENT
                R.style.PrimaryPink -> TextSecurePreferences.PINK_ACCENT
                R.style.PrimaryRed -> TextSecurePreferences.RED_ACCENT
                R.style.PrimaryOrange -> TextSecurePreferences.ORANGE_ACCENT
                R.style.PrimaryYellow -> TextSecurePreferences.YELLOW_ACCENT
                else -> null
            }
        )
    }

    override fun getThemeStyle(): String {
        val hasLegacy = getStringPreference(LEGACY_PREF_KEY_SELECTED_UI_MODE, null)
        if (!hasLegacy.isNullOrEmpty()) {
            migrateLegacyUiPref()
        }

        return getStringPreference(SELECTED_STYLE, CLASSIC_DARK)!!
    }

    override fun setThemeStyle(themeStyle: String) {
        val safeTheme = if (themeStyle !in listOf(CLASSIC_DARK, CLASSIC_LIGHT, OCEAN_DARK, OCEAN_LIGHT)) CLASSIC_DARK else themeStyle
        setStringPreference(SELECTED_STYLE, safeTheme)
    }

    override fun getFollowSystemSettings(): Boolean {
        val hasLegacy = getStringPreference(LEGACY_PREF_KEY_SELECTED_UI_MODE, null)
        if (!hasLegacy.isNullOrEmpty()) {
            migrateLegacyUiPref()
        }

        return getBooleanPreference(FOLLOW_SYSTEM_SETTINGS, false)
    }

    private fun migrateLegacyUiPref() {
        val legacy = getStringPreference(LEGACY_PREF_KEY_SELECTED_UI_MODE, null) ?: return
        val (mode, followSystem) = when (legacy) {
            "DAY" -> {
                CLASSIC_LIGHT to false
            }
            "NIGHT" -> {
                CLASSIC_DARK to false
            }
            "SYSTEM_DEFAULT" -> {
                CLASSIC_DARK to true
            }
            else -> {
                CLASSIC_DARK to false
            }
        }
        if (!hasPreference(FOLLOW_SYSTEM_SETTINGS) && !hasPreference(SELECTED_STYLE)) {
            setThemeStyle(mode)
            setFollowSystemSettings(followSystem)
        }
        removePreference(LEGACY_PREF_KEY_SELECTED_UI_MODE)
    }

    override fun setFollowSystemSettings(followSystemSettings: Boolean) {
        setBooleanPreference(FOLLOW_SYSTEM_SETTINGS, followSystemSettings)
    }

    override fun autoplayAudioMessages(): Boolean {
        return getBooleanPreference(AUTOPLAY_AUDIO_MESSAGES, false)
    }

    override fun clearAll() {
        getDefaultSharedPreferences(context).edit().clear().commit()
    }

    override fun getHidePassword() = getBooleanPreference(HIDE_PASSWORD, false)

    override fun setHidePassword(value: Boolean) {
        setBooleanPreference(HIDE_PASSWORD, value)
    }
}

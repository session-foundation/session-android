package org.session.libsession.utilities

import android.content.Context
import android.hardware.Camera
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StyleRes
import androidx.camera.core.CameraSelector
import androidx.core.content.edit
import androidx.preference.PreferenceManager.getDefaultSharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.file_server.FileServer
import org.session.libsession.utilities.TextSecurePreferences.Companion.AUTOPLAY_AUDIO_MESSAGES
import org.session.libsession.utilities.TextSecurePreferences.Companion.CALL_NOTIFICATIONS_ENABLED
import org.session.libsession.utilities.TextSecurePreferences.Companion.CLASSIC_DARK
import org.session.libsession.utilities.TextSecurePreferences.Companion.CLASSIC_LIGHT
import org.session.libsession.utilities.TextSecurePreferences.Companion.DEBUG_HAS_COPIED_DONATION_URL
import org.session.libsession.utilities.TextSecurePreferences.Companion.DEBUG_HAS_DONATED
import org.session.libsession.utilities.TextSecurePreferences.Companion.DEBUG_SEEN_DONATION_CTA_AMOUNT
import org.session.libsession.utilities.TextSecurePreferences.Companion.DEBUG_SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW
import org.session.libsession.utilities.TextSecurePreferences.Companion.ENVIRONMENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.FOLLOW_SYSTEM_SETTINGS
import org.session.libsession.utilities.TextSecurePreferences.Companion.FORCED_SHORT_TTL
import org.session.libsession.utilities.TextSecurePreferences.Companion.HAS_CHECKED_DOZE_WHITELIST
import org.session.libsession.utilities.TextSecurePreferences.Companion.HAS_COPIED_DONATION_URL
import org.session.libsession.utilities.TextSecurePreferences.Companion.HAS_DONATED
import org.session.libsession.utilities.TextSecurePreferences.Companion.HAS_SEEN_PRO_EXPIRED
import org.session.libsession.utilities.TextSecurePreferences.Companion.HAS_SEEN_PRO_EXPIRING
import org.session.libsession.utilities.TextSecurePreferences.Companion.HAVE_SHOWN_A_NOTIFICATION_ABOUT_TOKEN_PAGE
import org.session.libsession.utilities.TextSecurePreferences.Companion.HIDE_PASSWORD
import org.session.libsession.utilities.TextSecurePreferences.Companion.LAST_PATH_ROTATION
import org.session.libsession.utilities.TextSecurePreferences.Companion.LAST_SEEN_DONATION_CTA
import org.session.libsession.utilities.TextSecurePreferences.Companion.LAST_SNODE_POOL_REFRESH
import org.session.libsession.utilities.TextSecurePreferences.Companion.LAST_VACUUM_TIME
import org.session.libsession.utilities.TextSecurePreferences.Companion.LAST_VERSION_CHECK
import org.session.libsession.utilities.TextSecurePreferences.Companion.LEGACY_PREF_KEY_SELECTED_UI_MODE
import org.session.libsession.utilities.TextSecurePreferences.Companion.OCEAN_DARK
import org.session.libsession.utilities.TextSecurePreferences.Companion.OCEAN_LIGHT
import org.session.libsession.utilities.TextSecurePreferences.Companion.SEEN_DONATION_CTA_AMOUNT
import org.session.libsession.utilities.TextSecurePreferences.Companion.SELECTED_ACCENT_COLOR
import org.session.libsession.utilities.TextSecurePreferences.Companion.SELECTED_STYLE
import org.session.libsession.utilities.TextSecurePreferences.Companion.SEND_WITH_ENTER
import org.session.libsession.utilities.TextSecurePreferences.Companion.SHOWN_CALL_NOTIFICATION
import org.session.libsession.utilities.TextSecurePreferences.Companion.SHOWN_CALL_WARNING
import org.session.libsession.utilities.TextSecurePreferences.Companion.SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW
import org.session.libsession.utilities.TextSecurePreferences.Companion._events
import org.session.libsignal.utilities.Log
import java.io.IOException
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

interface TextSecurePreferences {

    fun getConfigurationMessageSynced(): Boolean
    fun setConfigurationMessageSynced(value: Boolean)

    fun setPushEnabled(value: Boolean)
    val pushEnabled: StateFlow<Boolean>

    fun isScreenLockEnabled(): Boolean
    fun setScreenLockEnabled(value: Boolean)
    fun getScreenLockTimeout(): Long
    fun setScreenLockTimeout(value: Long)
    fun setBackupPassphrase(passphrase: String?)
    fun getBackupPassphrase(): String?
    fun setEncryptedBackupPassphrase(encryptedPassphrase: String?)
    fun getEncryptedBackupPassphrase(): String?
    fun setBackupEnabled(value: Boolean)
    fun isBackupEnabled(): Boolean
    fun setNextBackupTime(time: Long)
    fun getNextBackupTime(): Long
    fun setBackupSaveDir(dirUri: String?)
    fun getBackupSaveDir(): String?
    fun getNeedsSqlCipherMigration(): Boolean
    fun isIncognitoKeyboardEnabled(): Boolean
    fun setIncognitoKeyboardEnabled(enabled : Boolean)
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
    fun getMessageBodyTextSize(): Int
    fun setPreferredCameraDirection(value: CameraSelector)
    fun getPreferredCameraDirection(): CameraSelector
    fun setNotificationPrivacy(string : String)
    fun getNotificationPrivacy(): NotificationPrivacyPreference
    fun getRepeatAlertsCount(): Int
    fun isInThreadNotifications(): Boolean
    fun isUniversalUnidentifiedAccess(): Boolean
    fun getUpdateApkRefreshTime(): Long
    fun setUpdateApkRefreshTime(value: Long)
    fun setUpdateApkDownloadId(value: Long)
    fun getUpdateApkDownloadId(): Long
    fun setUpdateApkDigest(value: String?)
    fun getUpdateApkDigest(): String?
    fun getHasLegacyConfig(): Boolean
    fun setHasLegacyConfig(newValue: Boolean)
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
    fun setSoundWhenAppIsOpenEnabled(enabled: Boolean)
    fun isSoundWhenAppIsOpenEnabled(): Boolean
    fun getNotificationLedColor(): Int
    fun setThreadLengthTrimmingEnabled(enabled : Boolean)
    fun isThreadLengthTrimmingEnabled(): Boolean
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
    fun setStringSetPreference(key: String, value: Set<String>)
    fun getLastOpenTimeDate(): Long
    fun setLastOpenDate()
    fun hasSeenLinkPreviewSuggestionDialog(): Boolean
    fun setHasSeenLinkPreviewSuggestionDialog()
    fun hasSeenProExpiring(): Boolean
    fun setHasSeenProExpiring()
    fun hasSeenProExpired(): Boolean
    fun setHasSeenProExpired()
    fun setShownCallWarning(): Boolean
    fun setShownCallNotification(): Boolean
    fun setCallNotificationsEnabled(enabled : Boolean)
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
    fun setAutoplayAudioMessages(enabled : Boolean)
    fun isAutoplayAudioMessagesEnabled(): Boolean
    fun hasForcedNewConfig(): Boolean
    fun hasPreference(key: String): Boolean
    fun clearAll()
    fun getHidePassword(): Boolean
    fun setHidePassword(value: Boolean)
    fun watchHidePassword(): StateFlow<Boolean>
    fun getLastVersionCheck(): Long
    fun setLastVersionCheck()
    fun getEnvironment(): Environment
    fun setEnvironment(value: Environment)
    fun hasSeenTokenPageNotification(): Boolean
    fun setHasSeenTokenPageNotification(value: Boolean)
    fun forcedShortTTL(): Boolean
    fun setForcedShortTTL(value: Boolean)

    fun setSubscriptionProvider(provider: String)
    fun getSubscriptionProvider(): String?

    fun hasCheckedDozeWhitelist(): Boolean
    fun setHasCheckedDozeWhitelist(hasChecked: Boolean)
    fun hasDonated(): Boolean
    fun setHasDonated(hasDonated: Boolean)
    fun hasCopiedDonationURL(): Boolean
    fun setHasCopiedDonationURL(hasCopied: Boolean)
    fun seenDonationCTAAmount(): Int
    fun setSeenDonationCTAAmount(amount: Int)
    fun lastSeenDonationCTA(): Long
    fun setLastSeenDonationCTA(timestamp: Long)
    fun showDonationCTAFromPositiveReview(): Boolean
    fun setShowDonationCTAFromPositiveReview(show: Boolean)

    fun hasDonatedDebug(): String?
    fun setHasDonatedDebug(hasDonated: String?)
    fun hasCopiedDonationURLDebug(): String?
    fun setHasCopiedDonationURLDebug(hasCopied: String?)
    fun seenDonationCTAAmountDebug(): String?
    fun setSeenDonationCTAAmountDebug(amount: String?)
    fun showDonationCTAFromPositiveReviewDebug(): String?
    fun setShowDonationCTAFromPositiveReviewDebug(show: String?)

    fun getLastSnodePoolRefresh(): Long
    fun setLastSnodePoolRefresh(epochMs: Long)
    fun getLastPathRotation(): Long
    fun setLastPathRotation(epochMs: Long)

    fun setSendWithEnterEnabled(enabled: Boolean)
    fun isSendWithEnterEnabled() : Boolean
    fun updateBooleanFromKey(key : String, value : Boolean)

    var deprecationStateOverride: String?
    var deprecatedTimeOverride: ZonedDateTime?
    var deprecatingStartTimeOverride: ZonedDateTime?
    var migratedToGroupV2Config: Boolean
    var migratedToDisablingKDF: Boolean

    var migratedDisappearingMessagesToMessageContent: Boolean

    var selectedActivityAliasName: String?

    var inAppReviewState: String?
    var forcesDeterministicAttachmentEncryption: Boolean
    var debugAvatarReupload: Boolean
    var alternativeFileServer: FileServer?


    companion object {
        val TAG = TextSecurePreferences::class.simpleName

        internal val _events = MutableSharedFlow<String>(0, 64, BufferOverflow.DROP_OLDEST)
        val events get() = _events.asSharedFlow()

        @JvmStatic
        var pushSuffix = ""


        const val DISABLE_PASSPHRASE_PREF = "pref_disable_passphrase"
        const val LANGUAGE_PREF = "pref_language"
        const val LAST_VERSION_CODE_PREF = "last_version_code"
        const val RINGTONE_PREF = "pref_key_ringtone"
        const val VIBRATE_PREF = "pref_key_vibrate"
        const val SOUND_WHEN_OPEN = "pref_sound_when_app_open"
        const val NOTIFICATION_PREF = "pref_key_enable_notifications"
        const val LED_COLOR_PREF_PRIMARY = "pref_led_color_primary"
        const val PASSPHRASE_TIMEOUT_INTERVAL_PREF = "pref_timeout_interval"
        const val PASSPHRASE_TIMEOUT_PREF = "pref_timeout_passphrase"
        const val THREAD_TRIM_ENABLED = "pref_trim_threads"
        const val UPDATE_APK_REFRESH_TIME_PREF = "pref_update_apk_refresh_time"
        const val UPDATE_APK_DOWNLOAD_ID = "pref_update_apk_download_id"
        const val UPDATE_APK_DIGEST = "pref_update_apk_digest"
        const val IN_THREAD_NOTIFICATION_PREF = "pref_key_inthread_notifications"
        const val MESSAGE_BODY_TEXT_SIZE_PREF = "pref_message_body_text_size"
        @Deprecated("No longer used, kept for migration purposes")
        const val REPEAT_ALERTS_PREF = "pref_repeat_alerts"
        const val NOTIFICATION_PRIVACY_PREF = "pref_notification_privacy"
        const val DIRECT_CAPTURE_CAMERA_ID = "pref_direct_capture_camera_id"
        const val READ_RECEIPTS_PREF = "pref_read_receipts"
        const val INCOGNITO_KEYBOARD_PREF = "pref_incognito_keyboard"
        const val NEEDS_SQLCIPHER_MIGRATION = "pref_needs_sql_cipher_migration"
        const val BACKUP_ENABLED = "pref_backup_enabled_v3"
        const val BACKUP_PASSPHRASE = "pref_backup_passphrase"
        const val ENCRYPTED_BACKUP_PASSPHRASE = "pref_encrypted_backup_passphrase"
        const val BACKUP_TIME = "pref_backup_next_time"
        const val BACKUP_SAVE_DIR = "pref_save_dir"
        const val SCREEN_LOCK = "pref_android_screen_lock"
        const val SCREEN_LOCK_TIMEOUT = "pref_android_screen_lock_timeout"
        const val NOTIFICATION_CHANNEL_VERSION = "pref_notification_channel_version"
        const val NOTIFICATION_MESSAGES_CHANNEL_VERSION = "pref_notification_messages_channel_version"
        const val UNIVERSAL_UNIDENTIFIED_ACCESS = "pref_universal_unidentified_access"
        const val TYPING_INDICATORS = "pref_typing_indicators"
        const val LINK_PREVIEWS = "pref_link_previews"
        const val GIF_METADATA_WARNING = "has_seen_gif_metadata_warning"
        const val GIF_GRID_LAYOUT = "pref_gif_grid_layout"
        val IS_PUSH_ENABLED get() = "pref_is_using_fcm$pushSuffix"
        const val CONFIGURATION_SYNCED = "pref_configuration_synced"
        const val PROFILE_PIC_EXPIRY = "profile_pic_expiry"
        const val LAST_OPEN_DATE = "pref_last_open_date"
        const val HAS_SEEN_PRO_EXPIRING = "has_seen_pro_expiring"
        const val HAS_SEEN_PRO_EXPIRED = "has_seen_pro_expired"
        const val CALL_NOTIFICATIONS_ENABLED = "pref_call_notifications_enabled"
        const val SHOWN_CALL_WARNING = "pref_shown_call_warning" // call warning is user-facing warning of enabling calls
        const val SHOWN_CALL_NOTIFICATION = "pref_shown_call_notification" // call notification is a prompt to check privacy settings
        const val LAST_VACUUM_TIME = "pref_last_vacuum_time"
        const val AUTOPLAY_AUDIO_MESSAGES = "pref_autoplay_audio"
        const val SEND_WITH_ENTER = "pref_enter_sends"
        const val FINGERPRINT_KEY_GENERATED = "fingerprint_key_generated"
        const val SELECTED_ACCENT_COLOR = "selected_accent_color"
        const val LAST_VERSION_CHECK = "pref_last_version_check"
        const val ENVIRONMENT = "debug_environment"
        const val MIGRATED_TO_GROUP_V2_CONFIG = "migrated_to_group_v2_config"
        const val MIGRATED_TO_DISABLING_KDF = "migrated_to_disabling_kdf"

        const val HAS_RECEIVED_LEGACY_CONFIG = "has_received_legacy_config"
        const val HAS_FORCED_NEW_CONFIG = "has_forced_new_config"

        const val GREEN_ACCENT = "accent_green"
        const val BLUE_ACCENT = "accent_blue"
        const val PURPLE_ACCENT = "accent_purple"
        const val PINK_ACCENT = "accent_pink"
        const val RED_ACCENT = "accent_red"
        const val ORANGE_ACCENT = "accent_orange"
        const val YELLOW_ACCENT = "accent_yellow"

        const val SELECTED_STYLE = "pref_selected_style" // classic_dark/light, ocean_dark/light
        const val FOLLOW_SYSTEM_SETTINGS = "pref_follow_system" // follow system day/night
        const val HIDE_PASSWORD = "pref_hide_password"

        const val LEGACY_PREF_KEY_SELECTED_UI_MODE = "SELECTED_UI_MODE" // this will be cleared upon launching app, for users migrating to theming build
        const val CLASSIC_DARK = "classic.dark"
        const val CLASSIC_LIGHT = "classic.light"
        const val OCEAN_DARK = "ocean.dark"
        const val OCEAN_LIGHT = "ocean.light"

        const val ALLOW_MESSAGE_REQUESTS = "libsession.ALLOW_MESSAGE_REQUESTS"

        const val DEPRECATED_STATE_OVERRIDE = "deprecation_state_override"
        const val DEPRECATED_TIME_OVERRIDE = "deprecated_time_override"
        const val DEPRECATING_START_TIME_OVERRIDE = "deprecating_start_time_override"

        // Key name for if we've warned the user that saving attachments will allow other apps to access them.
        // Note: We only ever display this once - and when the user has accepted the warning we never show it again
        // for the lifetime of the Session installation.
        const val HAVE_WARNED_USER_ABOUT_SAVING_ATTACHMENTS = "libsession.HAVE_WARNED_USER_ABOUT_SAVING_ATTACHMENTS"

        // As we will have an incoming push notification to inform the user about the new token page, but we
        // will also schedule instigating a local notification, we need to keep track of whether ANY notification
        // has been shown to the user, and if so we don't show another.
        const val HAVE_SHOWN_A_NOTIFICATION_ABOUT_TOKEN_PAGE = "pref_shown_a_notification_about_token_page"

        // Key name for the user's preferred date format string
        const val DATE_FORMAT_PREF = "libsession.DATE_FORMAT_PREF"

        // Key name for the user's preferred time format string
        const val TIME_FORMAT_PREF = "libsession.TIME_FORMAT_PREF"

        const val FORCED_SHORT_TTL = "forced_short_ttl"

        const val IN_APP_REVIEW_STATE = "in_app_review_state"

        const val SUBSCRIPTION_PROVIDER = "session_subscription_provider"
        const val DEBUG_AVATAR_REUPLOAD = "debug_avatar_reupload"

        const val HAS_CHECKED_DOZE_WHITELIST = "has_checked_doze_whitelist"

        // Donation
        const val HAS_DONATED = "has_donated"
        const val HAS_COPIED_DONATION_URL = "has_copied_donation_url"
        const val SEEN_DONATION_CTA_AMOUNT = "seen_donation_cta_amount"
        const val LAST_SEEN_DONATION_CTA = "last_seen_donation_cta"
        const val SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW = "show_donation_cta_from_positive_review"

        const val DEBUG_HAS_DONATED = "debug_has_donated"
        const val DEBUG_HAS_COPIED_DONATION_URL = "debug_has_copied_donation_url"
        const val DEBUG_SEEN_DONATION_CTA_AMOUNT = "debug_seen_donation_cta_amount"
        const val DEBUG_SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW = "debug_show_donation_cta_from_positive_review"

        const val LAST_SNODE_POOL_REFRESH = "last_snode_pool_refresh"
        const val LAST_PATH_ROTATION = "last_path_rotation"

        @JvmStatic
        fun isPushEnabled(context: Context): Boolean {
            return getBooleanPreference(context, IS_PUSH_ENABLED, false)
        }

        // endregion
        @JvmStatic
        fun isScreenLockEnabled(context: Context): Boolean {
            return getBooleanPreference(context, SCREEN_LOCK, false)
        }

        @JvmStatic
        fun setScreenLockEnabled(context: Context, value: Boolean) {
            setBooleanPreference(context, SCREEN_LOCK, value)
        }

        @JvmStatic
        fun getScreenLockTimeout(context: Context): Long {
            return getLongPreference(context, SCREEN_LOCK_TIMEOUT, 0)
        }

        @JvmStatic
        fun setScreenLockTimeout(context: Context, value: Long) {
            setLongPreference(context, SCREEN_LOCK_TIMEOUT, value)
        }

        @JvmStatic
        fun isIncognitoKeyboardEnabled(context: Context): Boolean {
            return getBooleanPreference(context, INCOGNITO_KEYBOARD_PREF, true)
        }

        @JvmStatic
        fun isReadReceiptsEnabled(context: Context): Boolean {
            return getBooleanPreference(context, READ_RECEIPTS_PREF, false)
        }

        @JvmStatic
        fun isGifSearchInGridLayout(context: Context): Boolean {
            return getBooleanPreference(context, GIF_GRID_LAYOUT, false)
        }

        @JvmStatic
        fun setIsGifSearchInGridLayout(context: Context, isGrid: Boolean) {
            setBooleanPreference(context, GIF_GRID_LAYOUT, isGrid)
        }

        @JvmStatic
        fun getNotificationPrivacy(context: Context): NotificationPrivacyPreference {
            return NotificationPrivacyPreference(getStringPreference(context, NOTIFICATION_PRIVACY_PREF, "all"))
        }

        @JvmStatic
        fun isPasswordDisabled(context: Context): Boolean {
            return getBooleanPreference(context, DISABLE_PASSPHRASE_PREF, true)
        }

        fun setPasswordDisabled(context: Context, disabled: Boolean) {
            setBooleanPreference(context, DISABLE_PASSPHRASE_PREF, disabled)
            _events.tryEmit(DISABLE_PASSPHRASE_PREF)
        }

        fun getLastVersionCode(context: Context): Int {
            return getIntegerPreference(context, LAST_VERSION_CODE_PREF, 0)
        }

        @Throws(IOException::class)
        fun setLastVersionCode(context: Context, versionCode: Int) {
            if (!setIntegerPreferenceBlocking(context, LAST_VERSION_CODE_PREF, versionCode)) {
                throw IOException("couldn't write version code to sharedpreferences")
            }
        }

        @JvmStatic
        fun isPassphraseTimeoutEnabled(context: Context): Boolean {
            return getBooleanPreference(context, PASSPHRASE_TIMEOUT_PREF, false)
        }

        @JvmStatic
        fun getPassphraseTimeoutInterval(context: Context): Int {
            return getIntegerPreference(context, PASSPHRASE_TIMEOUT_INTERVAL_PREF, 5 * 60)
        }

        @JvmStatic
        fun isNotificationsEnabled(context: Context): Boolean {
            return getBooleanPreference(context, NOTIFICATION_PREF, true)
        }

        @JvmStatic
        fun getNotificationRingtone(context: Context): Uri {
            var result = getStringPreference(context, RINGTONE_PREF, Settings.System.DEFAULT_NOTIFICATION_URI.toString())
            if (result != null && result.startsWith("file:")) {
                result = Settings.System.DEFAULT_NOTIFICATION_URI.toString()
            }
            return Uri.parse(result)
        }

        @JvmStatic
        fun isNotificationVibrateEnabled(context: Context): Boolean {
            return getBooleanPreference(context, VIBRATE_PREF, true)
        }

        @JvmStatic
        fun getNotificationLedColor(context: Context): Int {
            return getIntegerPreference(context, LED_COLOR_PREF_PRIMARY, ThemeUtil.getThemedColor(context, R.attr.colorAccent))
        }

        @JvmStatic
        fun isThreadLengthTrimmingEnabled(context: Context): Boolean {
            return getBooleanPreference(context, THREAD_TRIM_ENABLED, true)
        }

        @JvmStatic
        fun getNotificationChannelVersion(context: Context): Int {
            return getIntegerPreference(context, NOTIFICATION_CHANNEL_VERSION, 1)
        }

        @JvmStatic
        fun setNotificationChannelVersion(context: Context, version: Int) {
            setIntegerPreference(context, NOTIFICATION_CHANNEL_VERSION, version)
        }

        @JvmStatic
        fun getNotificationMessagesChannelVersion(context: Context): Int {
            return getIntegerPreference(context, NOTIFICATION_MESSAGES_CHANNEL_VERSION, 1)
        }

        @JvmStatic
        fun setNotificationMessagesChannelVersion(context: Context, version: Int) {
            setIntegerPreference(context, NOTIFICATION_MESSAGES_CHANNEL_VERSION, version)
        }

        @JvmStatic
        fun getBooleanPreference(context: Context, key: String?, defaultValue: Boolean): Boolean {
            return getDefaultSharedPreferences(context).getBoolean(key, defaultValue)
        }

        @JvmStatic
        fun setBooleanPreference(context: Context, key: String?, value: Boolean) {
            getDefaultSharedPreferences(context).edit().putBoolean(key, value).apply()
        }

        @JvmStatic
        fun getStringPreference(context: Context, key: String, defaultValue: String?): String? {
            return getDefaultSharedPreferences(context).getString(key, defaultValue)
        }

        @JvmStatic
        fun setStringPreference(context: Context, key: String?, value: String?) {
            getDefaultSharedPreferences(context).edit().putString(key, value).apply()
        }

        fun getIntegerPreference(context: Context, key: String, defaultValue: Int): Int {
            return getDefaultSharedPreferences(context).getInt(key, defaultValue)
        }

        private fun setIntegerPreference(context: Context, key: String, value: Int) {
            getDefaultSharedPreferences(context).edit().putInt(key, value).apply()
        }

        private fun setIntegerPreferenceBlocking(context: Context, key: String, value: Int): Boolean {
            return getDefaultSharedPreferences(context).edit().putInt(key, value).commit()
        }

        private fun getLongPreference(context: Context, key: String, defaultValue: Long): Long {
            return getDefaultSharedPreferences(context).getLong(key, defaultValue)
        }

        private fun setLongPreference(context: Context, key: String, value: Long) {
            getDefaultSharedPreferences(context).edit().putLong(key, value).apply()
        }

        private fun removePreference(context: Context, key: String) {
            getDefaultSharedPreferences(context).edit().remove(key).apply()
        }

        private fun getStringSetPreference(context: Context, key: String, defaultValues: Set<String>): Set<String>? {
            val prefs = getDefaultSharedPreferences(context)
            return if (prefs.contains(key)) {
                prefs.getStringSet(key, emptySet())
            } else {
                defaultValues
            }
        }

        @Deprecated("We no longer keep the profile expiry in prefs, we write them in the file instead. Keeping it here for migration purposes")
        @JvmStatic
        fun getProfileExpiry(context: Context): Long{
            return getLongPreference(context, PROFILE_PIC_EXPIRY, 0)
        }

        @JvmStatic
        fun isCallNotificationsEnabled(context: Context): Boolean {
            return getBooleanPreference(context, CALL_NOTIFICATIONS_ENABLED, false)
        }

        @JvmStatic
        fun getLastVacuumTime(context: Context): Long {
            return getLongPreference(context, LAST_VACUUM_TIME, 0)
        }

        @JvmStatic
        fun setLastVacuumNow(context: Context) {
            setLongPreference(context, LAST_VACUUM_TIME, System.currentTimeMillis())
        }

        @JvmStatic
        fun getFingerprintKeyGenerated(context: Context): Boolean {
            return getBooleanPreference(context, FINGERPRINT_KEY_GENERATED, false)
        }

        @JvmStatic
        fun setFingerprintKeyGenerated(context: Context) {
            setBooleanPreference(context, FINGERPRINT_KEY_GENERATED, true)
        }

        // ----- Get / set methods for if we have already warned the user that saving attachments will allow other apps to access them -----
        // Note: We only ever show the warning dialog about this ONCE - when the user accepts this fact we write true to the flag & never show again.
        @JvmStatic
        fun getHaveWarnedUserAboutSavingAttachments(context: Context): Boolean {
            return getBooleanPreference(context, HAVE_WARNED_USER_ABOUT_SAVING_ATTACHMENTS, false)
        }

        @JvmStatic
        fun setHaveWarnedUserAboutSavingAttachments(context: Context) {
            setBooleanPreference(context, HAVE_WARNED_USER_ABOUT_SAVING_ATTACHMENTS, true)
        }
    }
}

@Singleton
class AppTextSecurePreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
): TextSecurePreferences {
    private val hiddenPasswordState = MutableStateFlow(getBooleanPreference(HIDE_PASSWORD, false))

    override var migratedToGroupV2Config: Boolean
        get() = getBooleanPreference(TextSecurePreferences.MIGRATED_TO_GROUP_V2_CONFIG, false)
        set(value) = setBooleanPreference(TextSecurePreferences.MIGRATED_TO_GROUP_V2_CONFIG, value)

    override var migratedToDisablingKDF: Boolean
        get() = getBooleanPreference(TextSecurePreferences.MIGRATED_TO_DISABLING_KDF, false)
        set(value) = getDefaultSharedPreferences(context).edit(commit = true) {
            putBoolean(TextSecurePreferences.MIGRATED_TO_DISABLING_KDF, value)
        }

    override var migratedDisappearingMessagesToMessageContent: Boolean
        get() = getBooleanPreference("migrated_disappearing_messages_to_message_content", false)
        set(value) = setBooleanPreference("migrated_disappearing_messages_to_message_content", value)

    override fun getConfigurationMessageSynced(): Boolean {
        return getBooleanPreference(TextSecurePreferences.CONFIGURATION_SYNCED, false)
    }

    override var selectedActivityAliasName: String?
        get() = getStringPreference("selected_activity_alias_name", null)
        set(value) {
            setStringPreference("selected_activity_alias_name", value)
        }

    override fun setConfigurationMessageSynced(value: Boolean) {
        setBooleanPreference(TextSecurePreferences.CONFIGURATION_SYNCED, value)
        _events.tryEmit(TextSecurePreferences.CONFIGURATION_SYNCED)
    }

    override val pushEnabled: MutableStateFlow<Boolean> = MutableStateFlow(
        getBooleanPreference(TextSecurePreferences.IS_PUSH_ENABLED, false)
    )

    override fun setPushEnabled(value: Boolean) {
        setBooleanPreference(TextSecurePreferences.IS_PUSH_ENABLED, value)
        pushEnabled.value = value
    }

    override fun isScreenLockEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.SCREEN_LOCK, false)
    }

    override fun setScreenLockEnabled(value: Boolean) {
        setBooleanPreference(TextSecurePreferences.SCREEN_LOCK, value)
        _events.tryEmit(TextSecurePreferences.SCREEN_LOCK,)
    }

    override fun getScreenLockTimeout(): Long {
        return getLongPreference(TextSecurePreferences.SCREEN_LOCK_TIMEOUT, 0)
    }

    override fun setScreenLockTimeout(value: Long) {
        setLongPreference(TextSecurePreferences.SCREEN_LOCK_TIMEOUT, value)
    }

    override fun setBackupPassphrase(passphrase: String?) {
        setStringPreference(TextSecurePreferences.BACKUP_PASSPHRASE, passphrase)
    }

    override fun getBackupPassphrase(): String? {
        return getStringPreference(TextSecurePreferences.BACKUP_PASSPHRASE, null)
    }

    override fun setEncryptedBackupPassphrase(encryptedPassphrase: String?) {
        setStringPreference(TextSecurePreferences.ENCRYPTED_BACKUP_PASSPHRASE, encryptedPassphrase)
    }

    override fun getEncryptedBackupPassphrase(): String? {
        return getStringPreference(TextSecurePreferences.ENCRYPTED_BACKUP_PASSPHRASE, null)
    }

    override fun setBackupEnabled(value: Boolean) {
        setBooleanPreference(TextSecurePreferences.BACKUP_ENABLED, value)
    }

    override fun isBackupEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.BACKUP_ENABLED, false)
    }

    override fun setNextBackupTime(time: Long) {
        setLongPreference(TextSecurePreferences.BACKUP_TIME, time)
    }

    override fun getNextBackupTime(): Long {
        return getLongPreference(TextSecurePreferences.BACKUP_TIME, -1)
    }

    override fun setBackupSaveDir(dirUri: String?) {
        setStringPreference(TextSecurePreferences.BACKUP_SAVE_DIR, dirUri)
    }

    override fun getBackupSaveDir(): String? {
        return getStringPreference(TextSecurePreferences.BACKUP_SAVE_DIR, null)
    }

    override fun getNeedsSqlCipherMigration(): Boolean {
        return getBooleanPreference(TextSecurePreferences.NEEDS_SQLCIPHER_MIGRATION, false)
    }

    override fun isIncognitoKeyboardEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.INCOGNITO_KEYBOARD_PREF, true)
    }

    override fun setIncognitoKeyboardEnabled(enabled: Boolean) {
        setBooleanPreference(TextSecurePreferences.INCOGNITO_KEYBOARD_PREF, enabled)
        _events.tryEmit(TextSecurePreferences.INCOGNITO_KEYBOARD_PREF)
    }

    override fun isReadReceiptsEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.READ_RECEIPTS_PREF, false)
    }

    override fun setReadReceiptsEnabled(enabled: Boolean) {
        setBooleanPreference(TextSecurePreferences.READ_RECEIPTS_PREF, enabled)
        _events.tryEmit(TextSecurePreferences.READ_RECEIPTS_PREF)
    }

    override fun isTypingIndicatorsEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.TYPING_INDICATORS, false)
    }

    override fun setTypingIndicatorsEnabled(enabled: Boolean) {
        setBooleanPreference(TextSecurePreferences.TYPING_INDICATORS, enabled)
        _events.tryEmit(TextSecurePreferences.TYPING_INDICATORS)
    }

    override fun isLinkPreviewsEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.LINK_PREVIEWS, false)
    }

    override fun setLinkPreviewsEnabled(enabled: Boolean) {
        setBooleanPreference(TextSecurePreferences.LINK_PREVIEWS, enabled)
        _events.tryEmit(TextSecurePreferences.LINK_PREVIEWS)
    }

    override fun hasSeenGIFMetaDataWarning(): Boolean {
        return getBooleanPreference(TextSecurePreferences.GIF_METADATA_WARNING, false)
    }

    override fun setHasSeenGIFMetaDataWarning() {
        setBooleanPreference(TextSecurePreferences.GIF_METADATA_WARNING, true)
    }

    override fun isGifSearchInGridLayout(): Boolean {
        return getBooleanPreference(TextSecurePreferences.GIF_GRID_LAYOUT, false)
    }

    override fun setIsGifSearchInGridLayout(isGrid: Boolean) {
        setBooleanPreference(TextSecurePreferences.GIF_GRID_LAYOUT, isGrid)
    }

    override fun getMessageBodyTextSize(): Int {
        return getStringPreference(TextSecurePreferences.MESSAGE_BODY_TEXT_SIZE_PREF, "16")!!.toInt()
    }

    override fun setPreferredCameraDirection(value: CameraSelector) {
        setIntegerPreference(TextSecurePreferences.DIRECT_CAPTURE_CAMERA_ID,
            when(value){
                CameraSelector.DEFAULT_FRONT_CAMERA -> Camera.CameraInfo.CAMERA_FACING_FRONT
                else -> Camera.CameraInfo.CAMERA_FACING_BACK
            })
    }

    override fun getPreferredCameraDirection(): CameraSelector {
        return when(getIntegerPreference(TextSecurePreferences.DIRECT_CAPTURE_CAMERA_ID, Camera.CameraInfo.CAMERA_FACING_BACK)){
            Camera.CameraInfo.CAMERA_FACING_FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    override fun setNotificationPrivacy(string: String) {
        setStringPreference(TextSecurePreferences.NOTIFICATION_PRIVACY_PREF, string)
        _events.tryEmit(TextSecurePreferences.NOTIFICATION_PRIVACY_PREF)
    }

    override fun getNotificationPrivacy(): NotificationPrivacyPreference {
        return NotificationPrivacyPreference(getStringPreference(
            TextSecurePreferences.NOTIFICATION_PRIVACY_PREF, "all"))
    }

    override fun getRepeatAlertsCount(): Int {
        return try {
            getStringPreference(TextSecurePreferences.REPEAT_ALERTS_PREF, "0")!!.toInt()
        } catch (e: NumberFormatException) {
            Log.w(TextSecurePreferences.TAG, e)
            0
        }
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

    override fun getHasLegacyConfig(): Boolean {
        return getBooleanPreference(TextSecurePreferences.HAS_RECEIVED_LEGACY_CONFIG, false)
    }

    override fun setHasLegacyConfig(newValue: Boolean) {
        setBooleanPreference(TextSecurePreferences.HAS_RECEIVED_LEGACY_CONFIG, newValue)
        _events.tryEmit(TextSecurePreferences.HAS_RECEIVED_LEGACY_CONFIG)
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
        _events.tryEmit(TextSecurePreferences.RINGTONE_PREF)
    }

    override fun setNotificationRingtone(ringtone: String?) {
        setStringPreference(TextSecurePreferences.RINGTONE_PREF, ringtone)
        _events.tryEmit(TextSecurePreferences.RINGTONE_PREF)
    }

    override fun setNotificationVibrateEnabled(enabled: Boolean) {
        setBooleanPreference(TextSecurePreferences.VIBRATE_PREF, enabled)
        _events.tryEmit(TextSecurePreferences.VIBRATE_PREF)
    }

    override fun isNotificationVibrateEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.VIBRATE_PREF, true)
    }

    override fun setSoundWhenAppIsOpenEnabled(enabled: Boolean) {
        setBooleanPreference(TextSecurePreferences.SOUND_WHEN_OPEN, enabled)
        _events.tryEmit(TextSecurePreferences.SOUND_WHEN_OPEN)
    }

    override fun isSoundWhenAppIsOpenEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.SOUND_WHEN_OPEN, false)
    }

    override fun getNotificationLedColor(): Int {
        return getIntegerPreference(TextSecurePreferences.LED_COLOR_PREF_PRIMARY, context.getColor(R.color.accent_green))
    }

    override fun setThreadLengthTrimmingEnabled(enabled: Boolean) {
        setBooleanPreference(TextSecurePreferences.THREAD_TRIM_ENABLED, enabled)
        _events.tryEmit(TextSecurePreferences.THREAD_TRIM_ENABLED)
    }

    override fun isThreadLengthTrimmingEnabled(): Boolean {
        return getBooleanPreference(TextSecurePreferences.THREAD_TRIM_ENABLED, true)
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
        return getDefaultSharedPreferences(context).getBoolean(key, defaultValue)
    }

    override fun setBooleanPreference(key: String?, value: Boolean) {
        getDefaultSharedPreferences(context).edit().putBoolean(key, value).apply()
    }

    override fun getStringPreference(key: String, defaultValue: String?): String? {
        return getDefaultSharedPreferences(context).getString(key, defaultValue)
    }

    override fun setStringPreference(key: String?, value: String?) {
        getDefaultSharedPreferences(context).edit().putString(key, value).apply()
    }

    override fun getIntegerPreference(key: String, defaultValue: Int): Int {
        return getDefaultSharedPreferences(context).getInt(key, defaultValue)
    }

    override fun setIntegerPreference(key: String, value: Int) {
        getDefaultSharedPreferences(context).edit().putInt(key, value).apply()
    }

    override fun setIntegerPreferenceBlocking(key: String, value: Int): Boolean {
        return getDefaultSharedPreferences(context).edit().putInt(key, value).commit()
    }

    override fun getLongPreference(key: String, defaultValue: Long): Long {
        return getDefaultSharedPreferences(context).getLong(key, defaultValue)
    }

    override fun setLongPreference(key: String, value: Long) {
        getDefaultSharedPreferences(context).edit().putLong(key, value).apply()
    }

    override fun hasPreference(key: String): Boolean {
        return getDefaultSharedPreferences(context).contains(key)
    }

    override fun removePreference(key: String) {
        getDefaultSharedPreferences(context).edit().remove(key).apply()
    }

    override fun getStringSetPreference(key: String, defaultValues: Set<String>): Set<String>? {
        val prefs = getDefaultSharedPreferences(context)
        return if (prefs.contains(key)) {
            prefs.getStringSet(key, emptySet())
        } else {
            defaultValues
        }
    }

    override fun setStringSetPreference(key: String, value: Set<String>) {
        getDefaultSharedPreferences(context).edit { putStringSet(key, value) }
    }

    override fun getLastSnodePoolRefresh(): Long {
        return getLongPreference(LAST_SNODE_POOL_REFRESH, 0)
    }

    override fun setLastSnodePoolRefresh(epochMs: Long) {
        setLongPreference(LAST_SNODE_POOL_REFRESH, epochMs)
    }

    override fun getLastPathRotation(): Long {
        return getLongPreference(LAST_PATH_ROTATION, 0)
    }

    override fun setLastPathRotation(epochMs: Long) {
        setLongPreference(LAST_PATH_ROTATION, epochMs)
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

    override fun setCallNotificationsEnabled(enabled: Boolean) {
        setBooleanPreference(CALL_NOTIFICATIONS_ENABLED, enabled)
        _events.tryEmit(CALL_NOTIFICATIONS_ENABLED)
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
        } else BuildConfig.DEFAULT_ENVIRONMENT
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

    override fun hasSeenProExpiring(): Boolean {
        return getBooleanPreference(HAS_SEEN_PRO_EXPIRING, false)
    }

    override fun setHasSeenProExpiring() {
        setBooleanPreference(HAS_SEEN_PRO_EXPIRING, true)
    }

    override fun hasSeenProExpired(): Boolean {
        return getBooleanPreference(HAS_SEEN_PRO_EXPIRED, false)
    }

    override fun setHasSeenProExpired() {
        setBooleanPreference(HAS_SEEN_PRO_EXPIRED, true)
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
            SELECTED_ACCENT_COLOR, when (newColorStyle) {
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

    override fun setAutoplayAudioMessages(enabled: Boolean) {
        setBooleanPreference(AUTOPLAY_AUDIO_MESSAGES, enabled)
        _events.tryEmit(AUTOPLAY_AUDIO_MESSAGES)
    }

    override fun isAutoplayAudioMessagesEnabled(): Boolean {
        return getBooleanPreference(AUTOPLAY_AUDIO_MESSAGES, false)
    }

    /**
     * Clear all prefs and reset our observables
     */
    override fun clearAll() {
        pushEnabled.update { false }
        hiddenPasswordState.update { false }

        getDefaultSharedPreferences(context).edit(commit = true) { clear() }
    }

    override fun getHidePassword() = getBooleanPreference(HIDE_PASSWORD, false)

    override fun setHidePassword(value: Boolean) {
        setBooleanPreference(HIDE_PASSWORD, value)
        hiddenPasswordState.update { value }
    }

    override fun watchHidePassword(): StateFlow<Boolean> {
        return hiddenPasswordState
    }

    override fun hasSeenTokenPageNotification(): Boolean {
        return getBooleanPreference(HAVE_SHOWN_A_NOTIFICATION_ABOUT_TOKEN_PAGE, false)
    }

    override fun setHasSeenTokenPageNotification(value: Boolean) {
        setBooleanPreference(HAVE_SHOWN_A_NOTIFICATION_ABOUT_TOKEN_PAGE, value)
    }

    override fun forcedShortTTL(): Boolean {
        return getBooleanPreference(FORCED_SHORT_TTL, false)
    }

    override fun setForcedShortTTL(value: Boolean) {
        setBooleanPreference(FORCED_SHORT_TTL, value)
    }

    override var inAppReviewState: String?
        get() = getStringPreference(TextSecurePreferences.IN_APP_REVIEW_STATE, null)
        set(value) = setStringPreference(TextSecurePreferences.IN_APP_REVIEW_STATE, value)

    override var deprecationStateOverride: String?
        get() = getStringPreference(TextSecurePreferences.DEPRECATED_STATE_OVERRIDE, null)
        set(value) {
            if (value == null) {
                removePreference(TextSecurePreferences.DEPRECATED_STATE_OVERRIDE)
            } else {
                setStringPreference(TextSecurePreferences.DEPRECATED_STATE_OVERRIDE, value)
            }
        }

    override var deprecatedTimeOverride: ZonedDateTime?
        get() = getStringPreference(TextSecurePreferences.DEPRECATED_TIME_OVERRIDE, null)?.let(ZonedDateTime::parse)
        set(value) {
            if (value == null) {
                removePreference(TextSecurePreferences.DEPRECATED_TIME_OVERRIDE)
            } else {
                setStringPreference(TextSecurePreferences.DEPRECATED_TIME_OVERRIDE, value.toString())
            }
        }

    override var deprecatingStartTimeOverride: ZonedDateTime?
        get() = getStringPreference(TextSecurePreferences.DEPRECATING_START_TIME_OVERRIDE, null)?.let(ZonedDateTime::parse)
        set(value) {
            if (value == null) {
                removePreference(TextSecurePreferences.DEPRECATING_START_TIME_OVERRIDE)
            } else {
                setStringPreference(TextSecurePreferences.DEPRECATING_START_TIME_OVERRIDE, value.toString())
            }
        }
    override fun getSubscriptionProvider(): String? {
        return getStringPreference(TextSecurePreferences.SUBSCRIPTION_PROVIDER, null)
    }

    override fun setSubscriptionProvider(provider: String) {
        setStringPreference(TextSecurePreferences.SUBSCRIPTION_PROVIDER, provider)
    }

    override var forcesDeterministicAttachmentEncryption: Boolean
        get() = getBooleanPreference("forces_deterministic_attachment_upload", false)
        set(value) {
            setBooleanPreference("forces_deterministic_attachment_upload", value)
        }

    override var debugAvatarReupload: Boolean
        get() = getBooleanPreference(TextSecurePreferences.DEBUG_AVATAR_REUPLOAD, false)
        set(value) {
            setBooleanPreference(TextSecurePreferences.DEBUG_AVATAR_REUPLOAD, value)
            _events.tryEmit(TextSecurePreferences.DEBUG_AVATAR_REUPLOAD)
        }

    override var alternativeFileServer: FileServer?
        get() = getStringPreference("alternative_file_server", null)?.let {
            json.decodeFromString(it)
        }

        set(value) {
            setStringPreference("alternative_file_server", value?.let {
                json.encodeToString(it)
            })
        }

    override fun hasCheckedDozeWhitelist(): Boolean {
        return getBooleanPreference(HAS_CHECKED_DOZE_WHITELIST, false)
    }

    override fun setHasCheckedDozeWhitelist(hasChecked: Boolean) {
        setBooleanPreference(HAS_CHECKED_DOZE_WHITELIST, hasChecked)
        _events.tryEmit(HAS_CHECKED_DOZE_WHITELIST)
    }

    override fun hasDonated(): Boolean {
        return getBooleanPreference(HAS_DONATED, false)
    }
    override fun setHasDonated(hasDonated: Boolean) {
        setBooleanPreference(HAS_DONATED, hasDonated)
    }

    override fun hasCopiedDonationURL(): Boolean {
        return getBooleanPreference(HAS_COPIED_DONATION_URL, false)
    }
    override fun setHasCopiedDonationURL(hasCopied: Boolean) {
        setBooleanPreference(HAS_COPIED_DONATION_URL, hasCopied)
    }

    override fun seenDonationCTAAmount(): Int {
        return getIntegerPreference(SEEN_DONATION_CTA_AMOUNT, 0)
    }
    override fun setSeenDonationCTAAmount(amount: Int) {
        setIntegerPreference(SEEN_DONATION_CTA_AMOUNT, amount)
    }

    override fun lastSeenDonationCTA(): Long {
        return getLongPreference(LAST_SEEN_DONATION_CTA, 0)
    }
    override fun setLastSeenDonationCTA(timestamp: Long) {
        setLongPreference(LAST_SEEN_DONATION_CTA, timestamp)
    }

    override fun showDonationCTAFromPositiveReview(): Boolean {
        return getBooleanPreference(SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW, false)
    }
    override fun setShowDonationCTAFromPositiveReview(show: Boolean) {
        setBooleanPreference(SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW, show)
    }

    override fun hasDonatedDebug(): String? {
        return getStringPreference(DEBUG_HAS_DONATED, null)
    }
    override fun setHasDonatedDebug(hasDonated: String?) {
        setStringPreference(DEBUG_HAS_DONATED, hasDonated)
    }

    override fun hasCopiedDonationURLDebug(): String? {
        return getStringPreference(DEBUG_HAS_COPIED_DONATION_URL, null)
    }
    override fun setHasCopiedDonationURLDebug(hasCopied: String?) {
        setStringPreference(DEBUG_HAS_COPIED_DONATION_URL, hasCopied)
    }

    override fun seenDonationCTAAmountDebug(): String? {
        return getStringPreference(DEBUG_SEEN_DONATION_CTA_AMOUNT, null)
    }
    override fun setSeenDonationCTAAmountDebug(amount: String?) {
        setStringPreference(DEBUG_SEEN_DONATION_CTA_AMOUNT, amount)
    }

    override fun showDonationCTAFromPositiveReviewDebug(): String? {
        return getStringPreference(DEBUG_SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW, null)
    }
    override fun setShowDonationCTAFromPositiveReviewDebug(show: String?) {
        setStringPreference(DEBUG_SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW, show)
    }

    override fun setSendWithEnterEnabled(enabled: Boolean) {
        setBooleanPreference(SEND_WITH_ENTER, enabled)
        _events.tryEmit(SEND_WITH_ENTER)
    }

    override fun isSendWithEnterEnabled(): Boolean {
        return getBooleanPreference(SEND_WITH_ENTER, false)
    }

    override fun updateBooleanFromKey(key: String, value: Boolean) {
        setBooleanPreference(key, value)
        _events.tryEmit(key)
    }
}

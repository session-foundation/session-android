package org.thoughtcrime.securesms.preferences

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.isPasswordDisabled
import org.session.libsession.utilities.TextSecurePreferences.Companion.setScreenLockEnabled
import org.thoughtcrime.securesms.components.SwitchPreferenceCompat
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.areNotificationsEnabled
import org.thoughtcrime.securesms.util.IntentUtils
import java.time.Instant
import java.time.ZonedDateTime

@AndroidEntryPoint
class PrivacySettingsPreferenceFragment : CorrectedPreferenceFragment() {

    @Inject
    lateinit var configFactory: ConfigFactory

    @Inject
    lateinit var textSecurePreferences: TextSecurePreferences

    @Inject
    lateinit var typingStatusRepository: TypingStatusRepository

    override fun onCreate(paramBundle: Bundle?) {
        super.onCreate(paramBundle)
        findPreference<Preference>(TextSecurePreferences.SCREEN_LOCK)!!
            .onPreferenceChangeListener = ScreenLockListener()
        findPreference<Preference>(TextSecurePreferences.TYPING_INDICATORS)!!
            .onPreferenceChangeListener = TypingIndicatorsToggleListener()
        findPreference<Preference>(TextSecurePreferences.CALL_NOTIFICATIONS_ENABLED)!!
            .onPreferenceChangeListener = CallToggleListener(this) { setCall(it) }
        findPreference<PreferenceCategory>(getString(R.string.sessionMessageRequests))?.let { category ->
            SwitchPreferenceCompat(requireContext()).apply {
                key = TextSecurePreferences.ALLOW_MESSAGE_REQUESTS
                preferenceDataStore = object : PreferenceDataStore() {

                    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                        if (key == TextSecurePreferences.ALLOW_MESSAGE_REQUESTS) {
                            return configFactory.withMutableUserConfigs {
                                it.userProfile.getCommunityMessageRequests()
                            }
                        }
                        return super.getBoolean(key, defValue)
                    }

                    override fun putBoolean(key: String?, value: Boolean) {
                        if (key == TextSecurePreferences.ALLOW_MESSAGE_REQUESTS) {
                            configFactory.withMutableUserConfigs {
                                it.userProfile.setCommunityMessageRequests(value)
                            }

                            return
                        }
                        super.putBoolean(key, value)
                    }
                }
                title = getString(R.string.messageRequestsCommunities)
                summary = getString(R.string.messageRequestsCommunitiesDescription)
            }.let(category::addPreference)
        }
        initializeVisibility()

    }

    fun scrollToKey(key: String) {
        scrollToPreference(key)
    }

    fun scrollAndAutoToggle(key: String){
        lifecycleScope.launch {
            scrollToKey(key)
            delay(500) // slight delay to make the transition less jarring
            // Find the preference based on the provided key.
            val pref = findPreference<Preference>(key)
            // auto toggle for prefs that are switches
            pref?.let {
                // Check if it's a switch preference so we can toggle its checked state.
                if (it is SwitchPreferenceCompat) {
                    // force set to true here, and call the onPreferenceChangeListener
                    // defined further up so that custom behaviours are still applied
                    // Invoke the onPreferenceChangeListener with the new value.
                    it.onPreferenceChangeListener?.onPreferenceChange(it, true)
                }
            }
        }
    }

    private fun setCall(isEnabled: Boolean) {
        (findPreference<Preference>(TextSecurePreferences.CALL_NOTIFICATIONS_ENABLED) as SwitchPreferenceCompat?)!!.isChecked =
            isEnabled
        if (isEnabled && !areNotificationsEnabled(requireActivity())) {
            // show a dialog saying that calls won't work properly if you don't have notifications on at a system level
            showSessionDialog {
                title(R.string.sessionNotifications)
                text(R.string.callsNotificationsRequired)
                button(R.string.sessionNotifications) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, BuildConfig.APPLICATION_ID)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .takeIf { IntentUtils.isResolvable(requireContext(), it) }
                        ?.let { startActivity(it) }
                }
                button(R.string.dismiss)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_privacy)
    }

    override fun onResume() {
        super.onResume()
    }

    private fun initializeVisibility() {
        if (isPasswordDisabled(requireContext())) {
            val keyguardManager =
                requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguardManager.isKeyguardSecure) {
                findPreference<SwitchPreferenceCompat>(TextSecurePreferences.SCREEN_LOCK)!!.isChecked = false
                findPreference<Preference>(TextSecurePreferences.SCREEN_LOCK)!!.isEnabled = false
            }
        } else {
            findPreference<Preference>(TextSecurePreferences.SCREEN_LOCK)!!.isVisible = false
            findPreference<Preference>(TextSecurePreferences.SCREEN_LOCK_TIMEOUT)!!.isVisible = false
        }
    }

    private inner class ScreenLockListener : Preference.OnPreferenceChangeListener {
        override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
            val enabled = newValue as Boolean
            setScreenLockEnabled(context!!, enabled)
            val intent = Intent(context, KeyCachingService::class.java)
            intent.action = KeyCachingService.LOCK_TOGGLED_EVENT
            context!!.startService(intent)
            return true
        }
    }

    private inner class TypingIndicatorsToggleListener : Preference.OnPreferenceChangeListener {
        override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
            val enabled = newValue as Boolean
            if (!enabled) {
                typingStatusRepository.clear()
            }
            return true
        }
    }

}
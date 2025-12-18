package org.thoughtcrime.securesms.preferences

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.preference.Preference
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.components.SwitchPreferenceCompat
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.preferences.widgets.DropDownPreference
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.isWhitelistedFromDoze
import org.thoughtcrime.securesms.ui.requestDozeWhitelist
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalColors
import java.util.Arrays
import javax.inject.Inject

@AndroidEntryPoint
class NotificationsPreferenceFragment : CorrectedPreferenceFragment() {
    @Inject
    lateinit var prefs: TextSecurePreferences

    private var showWhitelistEnableDialog by mutableStateOf(false)
    private var showWhitelistDisableDialog by mutableStateOf(false)

    private var whiteListControl: SwitchPreferenceCompat? = null

    private var composeView: ComposeView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // We will wrap the existing screen in a framelayout in order to add custom compose content
        val preferenceView = super.onCreateView(inflater, container, savedInstanceState)

        val wrapper = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        wrapper.addView(
            preferenceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        composeView = ComposeView(requireContext())
        wrapper.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        return wrapper
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //set up compose content
        composeView?.apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setThemedContent {
                if(showWhitelistEnableDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            // hide dialog
                            showWhitelistEnableDialog = false
                        },
                        title = Phrase.from(context, R.string.runSessionBackground)
                            .put(APP_NAME_KEY, getString(R.string.app_name))
                            .format().toString(),
                        text = Phrase.from(context, R.string.runSessionBackgroundDescription)
                            .put(APP_NAME_KEY, getString(R.string.app_name))
                            .format().toString(),
                        buttons = listOf(
                            DialogButtonData(
                                text = GetString(getString(R.string.allow)),
                                qaTag = getString(R.string.qa_conversation_settings_dialog_whitelist_confirm),
                                onClick = {
                                    openSystemBgWhitelist()
                                }
                            ),
                            DialogButtonData(
                                text = GetString(getString(R.string.cancel)),
                                qaTag = getString(R.string.qa_conversation_settings_dialog_whitelist_cancel),
                            ),
                        )
                    )
                }

                if(showWhitelistDisableDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            // hide dialog
                            showWhitelistDisableDialog = false
                        },
                        title = stringResource(R.string.limitBackgroundActivity),
                        text = Phrase.from(context, R.string.limitBackgroundActivityDescription)
                            .put(APP_NAME_KEY, getString(R.string.app_name))
                            .format().toString(),
                        buttons = listOf(
                            DialogButtonData(
                                text = GetString("Change Setting"),
                                qaTag = getString(R.string.qa_conversation_settings_dialog_whitelist_confirm),
                                color = LocalColors.current.danger,
                                onClick = {
                                    // we can't disable it ourselves, but we can take the user to the right settings instead
                                    openBatteryOptimizationSettings()
                                }
                            ),
                            DialogButtonData(
                                text = GetString(getString(R.string.cancel)),
                                qaTag = getString(R.string.qa_conversation_settings_dialog_whitelist_cancel),
                            ),
                        )
                    )
                }
            }
        }
    }

    override fun onCreate(paramBundle: Bundle?) {
        super.onCreate(paramBundle)
        // whitelist control
        whiteListControl = findPreference<SwitchPreferenceCompat>("whitelist_background")!!
        whiteListControl?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                // if already whitelisted, show toast
                if(requireContext().isWhitelistedFromDoze()){
                    showWhitelistDisableDialog = true
                } else {
                    openSystemBgWhitelist()
                }
                true
            }

        // Set up FCM toggle
        val fcmKey = "pref_key_use_fcm"
        val fcmPreference: SwitchPreferenceCompat = findPreference(fcmKey)!!
        fcmPreference.isChecked = prefs.pushEnabled.value
        fcmPreference.setOnPreferenceChangeListener { _: Preference, newValue: Any ->
                prefs.setPushEnabled(newValue as Boolean)
                // open whitelist dialog when setting to slow mode if first time
                if(!newValue && !prefs.hasCheckedDozeWhitelist()){
                    showWhitelistEnableDialog = true
                    prefs.setHasCheckedDozeWhitelist(true)
                }
                true
            }

        fcmPreference.summary = when (BuildConfig.FLAVOR) {
            "huawei" -> getString(R.string.notificationsFastModeDescriptionHuawei)
            else -> getString(R.string.notificationsFastModeDescription)
        }

        prefs.setNotificationRingtone(
            NotificationChannels.getMessageRingtone(requireContext()).toString()
        )
        prefs.setNotificationVibrateEnabled(
            NotificationChannels.getMessageVibrate(requireContext())
        )

        findPreference<DropDownPreference>(TextSecurePreferences.RINGTONE_PREF)?.apply {
            setOnViewReady { updateRingtonePref() }
            onPreferenceChangeListener = RingtoneSummaryListener()
        }

        findPreference<DropDownPreference>(TextSecurePreferences.NOTIFICATION_PRIVACY_PREF)?.apply {
            setOnViewReady { setDropDownLabel(entry) }
            onPreferenceChangeListener = NotificationPrivacyListener()
        }

        findPreference<Preference>(TextSecurePreferences.VIBRATE_PREF)!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                NotificationChannels.updateMessageVibrate(requireContext(), newValue as Boolean)
                true
            }

        findPreference<Preference>(TextSecurePreferences.RINGTONE_PREF)!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val current = prefs.getNotificationRingtone()
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                intent.putExtra(
                    RingtoneManager.EXTRA_RINGTONE_TYPE,
                    RingtoneManager.TYPE_NOTIFICATION
                )
                intent.putExtra(
                    RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                    Settings.System.DEFAULT_NOTIFICATION_URI
                )
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                startActivityForResult(intent, 1)
                true
            }

        findPreference<Preference>("system_notifications")!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                intent.putExtra(
                    Settings.EXTRA_CHANNEL_ID,
                    NotificationChannels.getMessagesChannel(requireContext())
                )
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                startActivity(intent)
                true
            }

        initializeMessageVibrateSummary(findPreference<Preference>(TextSecurePreferences.VIBRATE_PREF) as SwitchPreferenceCompat?)
    }

    override fun onResume() {
        super.onResume()

        whiteListControl?.isChecked = requireContext().isWhitelistedFromDoze()
    }

    // Opens the system Battery Optimization settings
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }

            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Fallback: open the generic Battery Optimization settings screen
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
        }
    }

    private fun openSystemBgWhitelist(){
        requireActivity().requestDozeWhitelist()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_notifications)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == Activity.RESULT_OK && data != null) {
            var uri = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (Settings.System.DEFAULT_NOTIFICATION_URI == uri) {
                NotificationChannels.updateMessageRingtone(requireContext(), uri)
                prefs.removeNotificationRingtone()
            } else {
                uri = uri ?: Uri.EMPTY
                NotificationChannels.updateMessageRingtone(requireContext(), uri)
                prefs.setNotificationRingtone(uri.toString())
            }
            updateRingtonePref()
        }
    }

    private inner class RingtoneSummaryListener : Preference.OnPreferenceChangeListener {
        override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
            val pref = preference as? DropDownPreference ?: return false
            val value = newValue as? Uri
            if (value == null || TextUtils.isEmpty(value.toString())) {
                pref.setDropDownLabel(context?.getString(R.string.none))
            } else {
                RingtoneManager.getRingtone(activity, value)
                    ?.getTitle(activity)
                    ?.let { pref.setDropDownLabel(it) }

            }
            return true
        }
    }

    private fun updateRingtonePref() {
        val pref = findPreference<Preference>(TextSecurePreferences.RINGTONE_PREF)
        val listener: RingtoneSummaryListener =
            (pref?.onPreferenceChangeListener) as? RingtoneSummaryListener
                ?: return

        val uri = prefs.getNotificationRingtone()
        listener.onPreferenceChange(pref, uri)
    }

    private fun initializeMessageVibrateSummary(pref: SwitchPreferenceCompat?) {
        pref!!.isChecked = prefs.isNotificationVibrateEnabled()
    }

    private inner class NotificationPrivacyListener : Preference.OnPreferenceChangeListener {
        @SuppressLint("StaticFieldLeak")
        override fun onPreferenceChange(preference: Preference, value: Any): Boolean {
            // update drop down
            val pref = preference as? DropDownPreference ?: return false
            val entryIndex = Arrays.asList(*pref.entryValues).indexOf(value)

            pref.setDropDownLabel(
                if (entryIndex >= 0 && entryIndex < pref.entries.size
                ) pref.entries[entryIndex]
                else getString(R.string.unknown)
            )

            return true
        }
    }
}

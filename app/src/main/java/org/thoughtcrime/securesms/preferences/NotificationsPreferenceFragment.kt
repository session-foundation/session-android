package org.thoughtcrime.securesms.preferences

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.components.SwitchPreferenceCompat
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HideSimpleDialog
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.preferences.widgets.ComposePreference
import org.thoughtcrime.securesms.preferences.widgets.DropDownPreference
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.isWhitelistedFromDoze
import org.thoughtcrime.securesms.ui.requestDozeWhitelist
import org.thoughtcrime.securesms.ui.theme.LocalColors
import java.util.Arrays
import javax.inject.Inject

@AndroidEntryPoint
class NotificationsPreferenceFragment : CorrectedPreferenceFragment() {
    @Inject
    lateinit var prefs: TextSecurePreferences

    //todo WHITELIST uncomment prefs after seeing the dialog
//todo WHITELIST remove hardcoded strings
//todo WHITELIST looks like there is a visible empty pref at the bottom from the compose one? << Might need to rebuild the whole thing in compose

    var showWhitelistDialog by mutableStateOf(false)

    override fun onCreate(paramBundle: Bundle?) {
        super.onCreate(paramBundle)
        // whitelist control
        val whiteListControl = findPreference<Preference>("whitelist_background")!!
        whiteListControl.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                // if already whitelisted, show toast
                if(requireContext().isWhitelistedFromDoze()){
                    Toast.makeText(requireContext(), "Session is already whitelisted", Toast.LENGTH_SHORT).show()
                } else {
                    openSystemBgWhitelist()
                }
                true
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                prefs.pushEnabled.collect { enabled ->
                    whiteListControl.isVisible = !enabled
                }
            }
        }

        // Set up FCM toggle
        val fcmKey = "pref_key_use_fcm"
        val fcmPreference: SwitchPreferenceCompat = findPreference(fcmKey)!!
        fcmPreference.isChecked = prefs.pushEnabled.value
        fcmPreference.setOnPreferenceChangeListener { _: Preference, newValue: Any ->
                prefs.setPushEnabled(newValue as Boolean)
                // open whitelist dialog when setting to slow mode if first time
                if(!newValue && !prefs.hasCheckedDozeWhitelist()){
                    showWhitelistDialog = true
                    //prefs.setHasCheckedDozeWhitelist(true)
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

        //set up compose content
        findPreference<ComposePreference>("compose_data")!!.apply {
            setContent {
                if(showWhitelistDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            // hide dialog
                            showWhitelistDialog = false
                        },
                        title = "Allow Session to work in the background",
                        text = "Since you are using slow mode we recommend allowing Session to run in the background to help with receiving messages. Your system might still decide to slow down the process but this step might help getting messages more reliably. \nYou can set this later in the Settings > Notifications page.",
                        buttons = listOf(
                            DialogButtonData(
                                text = GetString("Allow"),
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
            }
        }

        initializeMessageVibrateSummary(findPreference<Preference>(TextSecurePreferences.VIBRATE_PREF) as SwitchPreferenceCompat?)
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

            // update notification
            object : AsyncTask<Void?, Void?, Void?>() {
                override fun doInBackground(vararg params: Void?): Void? {
                    ApplicationContext.getInstance(requireContext()).messageNotifier.updateNotification(
                        activity!!
                    )
                    return null
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
            return true
        }
    }
}

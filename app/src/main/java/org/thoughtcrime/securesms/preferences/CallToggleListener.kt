package org.thoughtcrime.securesms.preferences

import android.Manifest
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import org.session.libsession.utilities.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants.SESSION_FOUNDATION
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.setBooleanPreference
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.showSessionDialog

internal class CallToggleListener(
    private val context: Fragment,
    private val setCallback: (Boolean) -> Unit
) : Preference.OnPreferenceChangeListener {

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        if (newValue == false) return true


        val text = Phrase.from(context.requireContext(), R.string.callsVoiceAndVideoModalDescription)
            .format()

        // check if we've shown the info dialog and check for microphone permissions
        context.showSessionDialog {
            title(R.string.callsVoiceAndVideoBeta)
            text(text)
            button(R.string.enable, R.string.AccessibilityId_enable) { requestMicrophonePermission() }
            cancelButton()
        }

        return false
    }

    private fun requestMicrophonePermission() {
        Permissions.with(context)
            .request(Manifest.permission.RECORD_AUDIO)
            .onAllGranted {
                setBooleanPreference(
                    context.requireContext(),
                    TextSecurePreferences.CALL_NOTIFICATIONS_ENABLED,
                    true
                )
                setCallback(true)
            }
            .withPermanentDenialDialog(
                context.requireContext().getString(R.string.permissionsMicrophoneAccessRequired))
            .onAnyDenied { setCallback(false) }
            .execute()
    }
}

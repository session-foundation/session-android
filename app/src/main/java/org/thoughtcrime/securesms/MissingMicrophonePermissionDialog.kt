package org.thoughtcrime.securesms

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.thoughtcrime.securesms.permissions.SettingsDialog

class MissingMicrophonePermissionDialog {
    companion object {
        @JvmStatic
        fun show(context: Context) = SettingsDialog.show(
            context,
            Phrase.from(context, R.string.permissionsMicrophoneAccessRequired)
                .put(APP_NAME_KEY, context.getString(R.string.app_name))
                .format().toString()
        )
    }
}

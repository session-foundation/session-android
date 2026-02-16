package org.thoughtcrime.securesms.preferences

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.core.view.isInvisible
import androidx.preference.Preference
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.components.ExportLogsDialog
import org.thoughtcrime.securesms.ui.components.LogExporter
import org.thoughtcrime.securesms.ui.getSubbedCharSequence
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.ui.openUrl
import org.thoughtcrime.securesms.ui.setThemedContent
import javax.inject.Inject

@AndroidEntryPoint
class HelpSettingsActivity: ScreenLockActionBarActivity() {

    override val applyDefaultWindowInsets: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        setContentView(R.layout.activity_fragment_wrapper)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, HelpSettingsFragment())
            .commit()
    }
}

@AndroidEntryPoint
class HelpSettingsFragment: CorrectedPreferenceFragment() {

    companion object {
        private const val EXPORT_LOGS  = "export_logs"
        private const val TRANSLATE    = "translate_session"
        private const val FEEDBACK     = "feedback"
        private const val FAQ          = "faq"
        private const val SUPPORT      = "support"
        private const val CROWDIN_URL  = "https://getsession.org/translate"
        private const val FEEDBACK_URL = "https://getsession.org/survey"
        private const val FAQ_URL      = "https://getsession.org/faq"
        private const val SUPPORT_URL  = "https://sessionapp.zendesk.com/hc/en-us"
    }

    private var composeView: ComposeView? = null

    private var showExportLogDialog by mutableStateOf(false)

    @Inject
    lateinit var exporter: LogExporter

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
                if(showExportLogDialog) {
                    ExportLogsDialog(
                        logExporter = exporter,
                        onDismissRequest = {
                            showExportLogDialog = false
                        }
                    )
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_help)

        // String sub the summary text of the `export_logs` element in preferences_help.xml
        val exportPref = preferenceScreen.findPreference<Preference>(EXPORT_LOGS)
        exportPref?.summary = context?.getSubbedCharSequence(R.string.helpReportABugExportLogsDescription, APP_NAME_KEY to getString(R.string.app_name))

        // String sub the summary text of the `translate_session` element in preferences_help.xml
        val translatePref = preferenceScreen.findPreference<Preference>(TRANSLATE)
        translatePref?.title = context?.getSubbedCharSequence(R.string.helpHelpUsTranslateSession, APP_NAME_KEY to getString(R.string.app_name))
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            EXPORT_LOGS -> {
                shareLogs()
                true
            }
            TRANSLATE -> {
                requireContext().openUrl(CROWDIN_URL)
                true
            }
            FEEDBACK -> {
                requireContext().openUrl(FEEDBACK_URL)
                true
            }
            FAQ -> {
                requireContext().openUrl(FAQ_URL)
                true
            }
            SUPPORT -> {
                requireContext().openUrl(SUPPORT_URL)
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }

    private fun updateExportButtonAndProgressBarUI(exportJobRunning: Boolean) {
        this.activity?.runOnUiThread(Runnable {
            // Change export logs button text
            val exportLogsButton = this.activity?.findViewById(R.id.export_logs_button) as TextView?
            if (exportLogsButton == null) { Log.w("Loki", "Could not find export logs button view.") }
            exportLogsButton?.text = if (exportJobRunning) getString(R.string.cancel) else getString(R.string.helpReportABugExportLogs)

            // Show progress bar
            val exportProgressBar = this.activity?.findViewById(R.id.export_progress_bar) as ProgressBar?
            exportProgressBar?.isInvisible = !exportJobRunning
        })
    }

    private fun shareLogs() {
        Permissions.with(this)
            .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .maxSdkVersion(Build.VERSION_CODES.P)
            .withPermanentDenialDialog(requireContext().getSubbedString(R.string.permissionsStorageDeniedLegacy, APP_NAME_KEY to getString(R.string.app_name)))
            .onAnyDenied {
                val c = requireContext()
                val txt = c.getSubbedString(R.string.permissionsStorageDeniedLegacy, APP_NAME_KEY to getString(R.string.app_name))
                Toast.makeText(c, txt, Toast.LENGTH_LONG).show()
            }
            .onAllGranted {
                showExportLogDialog = true
            }
            .execute()
    }
}
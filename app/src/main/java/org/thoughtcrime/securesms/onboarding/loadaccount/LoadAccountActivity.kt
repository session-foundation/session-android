package org.thoughtcrime.securesms.onboarding.loadaccount

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.preferences.MessagingPreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.BaseActionBarActivity
import org.thoughtcrime.securesms.onboarding.manager.LoadAccountManager
import org.thoughtcrime.securesms.onboarding.messagenotifications.MessageNotificationsActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.ui.setComposeContent
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import org.thoughtcrime.securesms.util.start
import javax.inject.Inject

@AndroidEntryPoint
class LoadAccountActivity : BaseActionBarActivity() {

    @Inject
    internal lateinit var prefs: TextSecurePreferences
    @Inject
    lateinit var preferenceStorage: PreferenceStorage
    @Inject
    internal lateinit var loadAccountManager: LoadAccountManager

    private val viewModel: LoadAccountViewModel by viewModels()

    override val applyDefaultWindowInsets: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // only apply inset padding at the top, so the compose children can choose how to handle the bottom
        findViewById<View>(android.R.id.content).applySafeInsetsPaddings(
            consumeInsets = false,
            applyBottom = false,
        )

        supportActionBar?.setTitle(R.string.loadAccount)
        preferenceStorage[MessagingPreferences.CONFIGURATION_SYNCED] = false


        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val mnemonic = viewModel.events.first().mnemonic
                loadAccountManager.load(mnemonic)
                start<MessageNotificationsActivity>()
            }
        }

        setComposeContent {
            val state by viewModel.stateFlow.collectAsState()
            LoadAccountScreen(state, viewModel.qrErrors, viewModel::onChange, viewModel::onContinue, viewModel::onScanQrCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }
}

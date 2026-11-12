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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
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
        prefs.setConfigurationMessageSynced(false)


        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Waits for one event at a time so a double tap can't load twice. A failed load
                // goes back to waiting, so the user can correct the phrase and try again.
                while (true) {
                    val mnemonic = viewModel.events.first().mnemonic
                    try {
                        loadAccountManager.load(mnemonic)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Failed to load account", e)
                        viewModel.onLoadFailed(e)
                        continue
                    }

                    start<MessageNotificationsActivity>()
                    break
                }
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

    companion object {
        private const val TAG = "LoadAccountActivity"
    }
}

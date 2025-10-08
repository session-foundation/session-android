package org.thoughtcrime.securesms.preferences.prosettings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.ui.UINavigator
import javax.inject.Inject

@AndroidEntryPoint
class ProSettingsActivity: FullComposeScreenLockActivity() {

    @Inject
    lateinit var navigator: UINavigator<ProSettingsDestination>

    companion object {
        private const val EXTRA_START_DESTINATION = "start_destination"

        fun createIntent(
            context: Context,
            startDestination: ProSettingsDestination = ProSettingsDestination.Home
        ): Intent {
            return Intent(context, ProSettingsActivity::class.java).apply {
                putExtra(EXTRA_START_DESTINATION, startDestination)
            }
        }
    }

    @Composable
    override fun ComposeContent() {
        val startDestination = intent.getParcelableExtra<ProSettingsDestination>(
            EXTRA_START_DESTINATION
        ) ?: ProSettingsDestination.Home

        ProSettingsNavHost(
            navigator = navigator,
            startDestination = startDestination,
            onBack = this::finish
        )
    }
}

package org.thoughtcrime.securesms.preferences.prosettings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity

@AndroidEntryPoint
class ProSettingsActivity: FullComposeScreenLockActivity() {

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
        val startDestination = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_START_DESTINATION,
            ProSettingsDestination::class.java
        ) ?: ProSettingsDestination.Home

        ProSettingsNavHost(
            showClose = false,
            startDestination = startDestination,
            onBack = this::finish
        )
    }
}

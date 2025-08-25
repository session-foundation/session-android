package org.thoughtcrime.securesms.preferences.appearance

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity

@AndroidEntryPoint
class AppDisguiseSettingsActivity : FullComposeScreenLockActivity() {

    @Composable
    override fun ComposeContent() {
        AppDisguiseSettingsScreen(
            viewModel = hiltViewModel(),
            onBack = this::finish
        )
    }
}
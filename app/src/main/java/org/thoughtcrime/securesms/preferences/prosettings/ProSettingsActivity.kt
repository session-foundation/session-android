package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.ui.UINavigator
import javax.inject.Inject

@AndroidEntryPoint
class ProSettingsActivity: FullComposeScreenLockActivity() {

    @Inject
    lateinit var navigator: UINavigator<ProSettingsDestination>

    @Composable
    override fun ComposeContent() {
        ProSettingsNavHost(
            navigator = navigator,
            onBack = this::finish
        )
    }
}

package org.thoughtcrime.securesms.preferences

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.preferences.compose.NotificationsPreferenceScreen
import org.thoughtcrime.securesms.preferences.compose.NotificationsPreferenceViewModel

@AndroidEntryPoint
class NotificationSettingsActivity : FullComposeScreenLockActivity() {

    @Composable
    override fun ComposeContent() {
        val viewModel: NotificationsPreferenceViewModel by viewModels()

        NotificationsPreferenceScreen(
            viewModel = viewModel,
            onBackPressed = this::finish
        )
    }
}
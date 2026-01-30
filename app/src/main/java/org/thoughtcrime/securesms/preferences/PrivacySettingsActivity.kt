package org.thoughtcrime.securesms.preferences

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.preferences.compose.PrivacySettingsPreferenceScreen
import org.thoughtcrime.securesms.preferences.compose.PrivacySettingsPreferenceViewModel

@AndroidEntryPoint
class PrivacySettingsActivity :
    FullComposeScreenLockActivity() {

    companion object {
        const val SCROLL_KEY = "privacy_scroll_key"
        const val SCROLL_AND_TOGGLE_KEY = "privacy_scroll_and_toggle_key"
    }

    @Composable
    override fun ComposeContent() {
        val viewModel: PrivacySettingsPreferenceViewModel by viewModels()

        intent.extras?.let {
            viewModel.setScrollActions(
                scrollToKey = intent.getStringExtra(SCROLL_KEY),
                scrollAndToggleKey = intent.getStringExtra(SCROLL_AND_TOGGLE_KEY)
            )
        }

        PrivacySettingsPreferenceScreen(
            viewModel = viewModel,
            onBackPressed = this::finish
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }
}
package org.thoughtcrime.securesms.preferences

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.preferences.compose.PrivacySettingsPreferenceScreen
import org.thoughtcrime.securesms.preferences.compose.PrivacySettingsPreferenceViewModel
import kotlin.getValue

@AndroidEntryPoint
class PrivacySettingsActivity :
//    ScreenLockActionBarActivity()
    FullComposeScreenLockActivity()
{

    @Composable
    override fun ComposeContent() {
        val viewModel: PrivacySettingsPreferenceViewModel by viewModels()
        PrivacySettingsPreferenceScreen(
            viewModel = viewModel,
            onBackPressed = this::finish
        )
    }

    companion object {
        const val SCROLL_KEY = "privacy_scroll_key"
        const val SCROLL_AND_TOGGLE_KEY = "privacy_scroll_and_toggle_key"
    }

//    override val applyDefaultWindowInsets: Boolean
//        get() = false
//
//    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
//        super.onCreate(savedInstanceState, ready)
//        setContentView(R.layout.activity_fragment_wrapper)
//        val fragment = PrivacySettingsPreferenceFragment()
//        val transaction = supportFragmentManager.beginTransaction()
//        transaction.replace(R.id.fragmentContainer, fragment)
//        transaction.commit()
//
//        if (intent.hasExtra(SCROLL_KEY)) {
//            fragment.scrollToKey(intent.getStringExtra(SCROLL_KEY)!!)
//        } else if (intent.hasExtra(SCROLL_AND_TOGGLE_KEY)) {
//            fragment.scrollAndAutoToggle(intent.getStringExtra(SCROLL_AND_TOGGLE_KEY)!!)
//
//        }
//    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }
}
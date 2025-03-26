package org.thoughtcrime.securesms.preferences

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import javax.inject.Inject

@AndroidEntryPoint
class PrivacySettingsActivity : ScreenLockActionBarActivity() {

    companion object{
        const val SCROLL_KEY = "privacy_scroll_key"
        const val SCROLL_AND_TOGGLE_KEY = "privacy_scroll_and_toggle_key"
    }

    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        setContentView(R.layout.activity_fragment_wrapper)
        val fragment = PrivacySettingsPreferenceFragment()
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.fragmentContainer, fragment)
        transaction.commit()

        if(intent.hasExtra(SCROLL_KEY)) {
            fragment.scrollToKey(intent.getStringExtra(SCROLL_KEY)!!)
        } else if(intent.hasExtra(SCROLL_AND_TOGGLE_KEY)) {
            fragment.scrollAndAutoToggle(intent.getStringExtra(SCROLL_AND_TOGGLE_KEY)!!)

        }
    }
}
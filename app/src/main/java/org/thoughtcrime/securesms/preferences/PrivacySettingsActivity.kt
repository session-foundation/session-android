package org.thoughtcrime.securesms.preferences

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.thoughtcrime.securesms.ScreenLockActionBarActivity

@AndroidEntryPoint
class PrivacySettingsActivity : ScreenLockActionBarActivity() {

    companion object{
        const val SCROLL_KEY = "privacy_scroll_key"
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
        }
    }
}
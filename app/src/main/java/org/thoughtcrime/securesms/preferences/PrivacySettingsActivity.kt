package org.thoughtcrime.securesms.preferences

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.thoughtcrime.securesms.ScreenLockActionBarActivity

@AndroidEntryPoint
class PrivacySettingsActivity : ScreenLockActionBarActivity() {

    companion object{
        const val SCROLL_KEY = "privacy_scroll_key"
        const val SCROLL_AND_TOGGLE_KEY = "privacy_scroll_and_toggle_key"
    }

    override val applyDefaultWindowInsets: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
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
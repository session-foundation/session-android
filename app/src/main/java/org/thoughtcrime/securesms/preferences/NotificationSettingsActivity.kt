package org.thoughtcrime.securesms.preferences

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.thoughtcrime.securesms.ScreenLockActionBarActivity

@AndroidEntryPoint
class NotificationSettingsActivity : ScreenLockActionBarActivity() {

    override val applyDefaultWindowInsets: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        setContentView(R.layout.activity_fragment_wrapper)
        supportActionBar!!.title = resources.getString(R.string.sessionNotifications)
        val fragment = NotificationsPreferenceFragment()
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.fragmentContainer, fragment)
        transaction.commit()
    }
}
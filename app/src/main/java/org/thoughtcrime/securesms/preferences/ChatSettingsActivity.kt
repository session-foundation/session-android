package org.thoughtcrime.securesms.preferences

import android.os.Bundle
import network.loki.messenger.R
import org.thoughtcrime.securesms.ScreenLockActionBarActivity

class ChatSettingsActivity : ScreenLockActionBarActivity() {

    override val applyDefaultWindowInsets: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        setContentView(R.layout.activity_fragment_wrapper)
        supportActionBar!!.title = resources.getString(R.string.sessionConversations)
        val fragment = ChatsPreferenceFragment()
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.fragmentContainer, fragment)
        transaction.commit()
    }
}
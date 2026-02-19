package org.thoughtcrime.securesms.onboarding.landing

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.preferences.SecurityPreferences
import org.thoughtcrime.securesms.BaseActionBarActivity
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.onboarding.loadaccount.LoadAccountActivity
import org.thoughtcrime.securesms.onboarding.pickname.startPickDisplayNameActivity
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.ui.setComposeContent
import org.thoughtcrime.securesms.util.setUpActionBarSessionLogo
import org.thoughtcrime.securesms.util.start
import javax.inject.Inject

@AndroidEntryPoint
class LandingActivity: BaseActionBarActivity() {

    @Inject
    internal lateinit var prefs: TextSecurePreferences

    @Inject
    lateinit var preferenceStorage: PreferenceStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // We always hit this LandingActivity on launch - but if there is a previous instance of
        // Session then close this activity to resume the last activity from the previous instance.
        if (!isTaskRoot) { finish(); return }

        setUpActionBarSessionLogo(true)

        setComposeContent {
            LandingScreen(
                createAccount = { startPickDisplayNameActivity() },
                loadAccount = { start<LoadAccountActivity>() }
            )
        }

        IdentityKeyUtil.generateIdentityKeyPair(this)
        preferenceStorage[SecurityPreferences.PASSWORD_DISABLED] = true
        // AC: This is a temporary workaround to trick the old code that the screen is unlocked.
        KeyCachingService.setMasterSecret(applicationContext, Object())
    }
}

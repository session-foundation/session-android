package org.thoughtcrime.securesms.recoverypassword

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.BaseActionBarActivity
import org.thoughtcrime.securesms.ui.setComposeContent

@AndroidEntryPoint
class RecoveryPasswordActivity : BaseActionBarActivity() {

    companion object {
        const val RESULT_RECOVERY_HIDDEN = "recovery_hidden"
    }

    private val viewModel: RecoveryPasswordViewModel by viewModels()

    @Inject lateinit var prefs: TextSecurePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar!!.title = resources.getString(R.string.sessionRecoveryPassword)

        setComposeContent {
            val mnemonic by viewModel.mnemonic.collectAsState("")
            val seed by viewModel.seed.collectAsState(null)

            RecoveryPasswordScreen(
                mnemonic = mnemonic,
                seed = seed,
                confirmHideRecovery = {
                    prefs.setHidePassword(true)

                    finish()
                },
                copyMnemonic = viewModel::copyMnemonic
            )
        }

        // Set the seed as having been viewed when the user has seen this activity, which
        // removes the reminder banner on the HomeActivity.
        prefs.setHasViewedSeed(true)
    }
}

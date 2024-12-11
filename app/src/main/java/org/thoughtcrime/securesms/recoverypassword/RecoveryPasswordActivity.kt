package org.thoughtcrime.securesms.recoverypassword

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.BaseActionBarActivity
import org.thoughtcrime.securesms.ui.setComposeContent
import javax.inject.Inject

@AndroidEntryPoint
class RecoveryPasswordActivity : BaseActionBarActivity() {

    @Inject
    internal lateinit var prefs: TextSecurePreferences

    companion object {
        const val RESULT_RECOVERY_HIDDEN = "recovery_hidden"
    }

    private val viewModel: RecoveryPasswordViewModel by viewModels()

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
                    val returnIntent = Intent()
                    returnIntent.putExtra(RESULT_RECOVERY_HIDDEN, true)
                    setResult(RESULT_OK, returnIntent)

                    // Hiding the recovery password permanently implies that you have viewed the seed - this then
                    // hides the banner about viewing the recovery password.
                    prefs.setHasViewedSeed(true)
                    prefs.getHasViewedSeed()

                    finish()
                },
                copyMnemonic = viewModel::copyMnemonic
            )
        }
    }
}

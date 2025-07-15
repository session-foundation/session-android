package org.thoughtcrime.securesms.conversation.v2.settings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import javax.inject.Inject

@AndroidEntryPoint
class ConversationSettingsActivity: FullComposeScreenLockActivity() {

    companion object {
        const val THREAD_ADDRESS = "conversation_settings_thread_address"

        fun createIntent(context: Context, address: Address): Intent {
            return Intent(context, ConversationSettingsActivity::class.java).apply {
                putExtra(THREAD_ADDRESS, address)
            }
        }
    }

    @Inject
    lateinit var navigator: ConversationSettingsNavigator

    @Composable
    override fun ComposeContent() {
        ConversationSettingsNavHost(
            address =  requireNotNull(IntentCompat.getParcelableExtra(intent, THREAD_ADDRESS, Address::class.java)) {
                "ConversationSettingsActivity requires an Address to be passed in the intent."
            },
            navigator = navigator,
            returnResult = { code, value ->
                setResult(RESULT_OK, Intent().putExtra(code, value))
                finish()
            },
            onBack = this::finish
        )
    }
}

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
        const val THREAD_ID = "conversation_settings_thread_id"
        const val THREAD_ADDRESS = "conversation_settings_thread_address"

        fun createIntent(context: Context, threadId: Long, threadAddress: Address?): Intent {
            return Intent(context, ConversationSettingsActivity::class.java).apply {
                putExtra(THREAD_ID, threadId)
                putExtra(THREAD_ADDRESS, threadAddress)
            }
        }
    }

    @Inject
    lateinit var navigator: ConversationSettingsNavigator

    @Composable
    override fun ComposeContent() {
        ConversationSettingsNavHost(
            threadId = intent.getLongExtra(THREAD_ID, 0),
            threadAddress =   IntentCompat.getParcelableExtra(intent, THREAD_ADDRESS, Address::class.java),
            navigator = navigator,
            returnResult = { code, value ->
                setResult(RESULT_OK, Intent().putExtra(code, value))
                finish()
            },
            onBack = this::finish
        )
    }
}

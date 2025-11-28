package org.thoughtcrime.securesms.conversation.v2.settings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.FullComposeScreenLockActivity

@AndroidEntryPoint
class ConversationSettingsActivity: FullComposeScreenLockActivity() {

    companion object {
        private const val THREAD_ADDRESS = "conversation_settings_thread_address"
        private const val EXTRA_START_DESTINATION = "start_destination"

        fun createIntent(
            context: Context,
            address: Address.Conversable,
            startDestination: ConversationSettingsDestination = ConversationSettingsDestination.RouteConversationSettings
        ): Intent {
            return Intent(context, ConversationSettingsActivity::class.java).apply {
                putExtra(THREAD_ADDRESS, address)
                putExtra(EXTRA_START_DESTINATION, startDestination)
            }
        }
    }

    @Composable
    override fun ComposeContent() {
        val startDestination = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_START_DESTINATION,
            ConversationSettingsDestination::class.java
        ) ?:  ConversationSettingsDestination.RouteConversationSettings

        ConversationSettingsNavHost(
            address = requireNotNull(IntentCompat.getParcelableExtra(intent, THREAD_ADDRESS, Address.Conversable::class.java)) {
                "ConversationSettingsActivity requires an Address to be passed in the intent."
            },
            startDestination = startDestination,
            returnResult = { code, value ->
                setResult(RESULT_OK, Intent().putExtra(code, value))
                finish()
            },
            onBack = this::finish
        )
    }
}

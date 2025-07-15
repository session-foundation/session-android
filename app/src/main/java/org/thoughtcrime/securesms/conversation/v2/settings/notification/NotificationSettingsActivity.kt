package org.thoughtcrime.securesms.conversation.v2.settings.notification

import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.FullComposeScreenLockActivity

/**
 * Forced to add an activity entry point for this screen
 * (which is otherwise accessed without an activity through the ConversationSettingsNavHost)
 * because this is navigated to from the conversation app bar
 */
@AndroidEntryPoint
class NotificationSettingsActivity: FullComposeScreenLockActivity() {

    @Composable
    override fun ComposeContent() {
        val viewModel =
            hiltViewModel<NotificationSettingsViewModel, NotificationSettingsViewModel.Factory> { factory ->
                factory.create(requireNotNull(
                    IntentCompat.getParcelableExtra(intent, ARG_ADDRESS, Address::class.java)
                ) {
                    "NotificationSettingsActivity requires an Address to be passed in via the intent."
                })
            }

        NotificationSettingsScreen(
            viewModel = viewModel,
            onBack = { finish() }
        )
    }

    companion object {
        const val ARG_ADDRESS = "address"
    }
}

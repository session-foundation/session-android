package org.thoughtcrime.securesms.conversation.v2.settings.notification

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.conversation.disappearingmessages.DisappearingMessagesActivity

/**
 * Forced to add an activity entry point for this screen
 * (which is otherwise accessed without an activity through the ConversationSettingsNavHost)
 * because this is navigated to from the conversation app bar
 */
@AndroidEntryPoint
class NotificationSettingsActivity: FullComposeScreenLockActivity() {

    private val threadId: Long by lazy {
        intent.getLongExtra(DisappearingMessagesActivity.THREAD_ID, -1)
    }

    @Composable
    override fun ComposeContent() {
        val viewModel =
            hiltViewModel<NotificationSettingsViewModel, NotificationSettingsViewModel.Factory> { factory ->
                factory.create(threadId)
            }

        NotificationSettingsScreen(
            viewModel = viewModel,
            onBack = { finish() }
        )
    }

    companion object {
        const val THREAD_ID = "thread_id"
    }
}

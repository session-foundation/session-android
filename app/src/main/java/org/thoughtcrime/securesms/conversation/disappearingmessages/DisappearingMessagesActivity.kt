package org.thoughtcrime.securesms.conversation.disappearingmessages

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.BuildConfig
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.conversation.disappearingmessages.ui.DisappearingMessagesScreen

@AndroidEntryPoint
class DisappearingMessagesActivity: FullComposeScreenLockActivity() {

    private val threadId: Long by lazy {
        intent.getLongExtra(THREAD_ID, -1)
    }

    @Composable
    override fun ComposeContent() {
        val viewModel: DisappearingMessagesViewModel =
            hiltViewModel<DisappearingMessagesViewModel, DisappearingMessagesViewModel.Factory> { factory ->
                factory.create(
                    threadId = threadId,
                    isNewConfigEnabled = ExpirationConfiguration.isNewConfigEnabled,
                    showDebugOptions = BuildConfig.DEBUG
                )
            }

        DisappearingMessagesScreen(
            viewModel = viewModel,
            onBack = { finish() },
        )
    }

    companion object {
        const val THREAD_ID = "thread_id"
    }
}

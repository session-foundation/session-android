package org.thoughtcrime.securesms.conversation.disappearingmessages

import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.BuildConfig
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.conversation.disappearingmessages.ui.DisappearingMessagesScreen

@AndroidEntryPoint
class DisappearingMessagesActivity: FullComposeScreenLockActivity() {

    @Composable
    override fun ComposeContent() {
        val viewModel: DisappearingMessagesViewModel =
            hiltViewModel<DisappearingMessagesViewModel, DisappearingMessagesViewModel.Factory> { factory ->
                factory.create(
                    address = requireNotNull(
                        IntentCompat.getParcelableExtra(intent, ARG_ADDRESS, Address::class.java)
                    ) {
                        "DisappearingMessagesActivity requires an Address to be passed in via the intent."
                    },
                    isNewConfigEnabled = ExpirationConfiguration.isNewConfigEnabled,
                    showDebugOptions = BuildConfig.BUILD_TYPE != "release"
                )
            }

        DisappearingMessagesScreen(
            viewModel = viewModel,
            onBack = { finish() },
        )
    }

    companion object {
        const val ARG_ADDRESS = "address"
    }
}

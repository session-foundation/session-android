package org.thoughtcrime.securesms.preferences

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity

@AndroidEntryPoint
class BlockedContactsActivity: FullComposeScreenLockActivity() {

    @Composable
    override fun ComposeContent() {
        val viewModel: BlockedContactsViewModel = hiltViewModel<BlockedContactsViewModel>()

        BlockedContactsScreen(
            viewModel = viewModel,
            onBack = { finish() },
        )
    }
}

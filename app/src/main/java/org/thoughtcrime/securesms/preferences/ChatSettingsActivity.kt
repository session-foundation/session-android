package org.thoughtcrime.securesms.preferences

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.preferences.compose.ConversationsPreferenceScreen
import org.thoughtcrime.securesms.preferences.compose.ChatsPreferenceViewModel

@AndroidEntryPoint
class ChatSettingsActivity : FullComposeScreenLockActivity() {

    @Composable
    override fun ComposeContent() {
        val viewModel: ChatsPreferenceViewModel by viewModels()

        ConversationsPreferenceScreen(
            viewModel = viewModel,
            onBlockedContactsClicked = {
                startActivity(Intent(this, BlockedContactsActivity::class.java))
            },
            onBackPressed = this::finish
        )
    }
}

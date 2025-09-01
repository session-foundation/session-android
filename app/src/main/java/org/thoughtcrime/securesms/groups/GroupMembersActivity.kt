package org.thoughtcrime.securesms.groups

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.groups.compose.GroupMembersScreen

/**
 * Forced to add an activity entry point for this screen
 * (which is otherwise accessed without an activity through the ConversationSettingsNavHost)
 * because this is navigated to from the conversation app bar
 */
@AndroidEntryPoint
class GroupMembersActivity: FullComposeScreenLockActivity() {

    @Composable
    override fun ComposeContent() {
        val viewModel: GroupMembersViewModel =
            hiltViewModel<GroupMembersViewModel, GroupMembersViewModel.Factory> { factory ->
                factory.create(IntentCompat.getParcelableExtra(intent, GROUP_ADDRESS, Address.Group::class.java)!!)
            }

        GroupMembersScreen(
            viewModel = viewModel,
            onBack = { finish() },
        )
    }

    companion object {
        fun createIntent(context: Context, address: Address.Group): Intent {
            return Intent(context, GroupMembersActivity::class.java).apply {
                putExtra(GROUP_ADDRESS, address)
            }
        }

        private const val GROUP_ADDRESS = "group_address"
    }
}

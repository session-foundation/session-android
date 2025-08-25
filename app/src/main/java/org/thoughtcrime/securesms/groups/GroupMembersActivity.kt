package org.thoughtcrime.securesms.groups

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.groups.compose.GroupMembersScreen

/**
 * Forced to add an activity entry point for this screen
 * (which is otherwise accessed without an activity through the ConversationSettingsNavHost)
 * because this is navigated to from the conversation app bar
 */
@AndroidEntryPoint
class GroupMembersActivity: FullComposeScreenLockActivity() {

    private val groupId: String by lazy {
        intent.getStringExtra(GROUP_ID) ?: ""
    }

    @Composable
    override fun ComposeContent() {
        val viewModel: GroupMembersViewModel =
            hiltViewModel<GroupMembersViewModel, GroupMembersViewModel.Factory> { factory ->
                factory.create(AccountId(groupId))
            }

        GroupMembersScreen(
            viewModel = viewModel,
            onBack = { finish() },
        )
    }

    companion object {
        const val GROUP_ID = "group_id"
    }
}

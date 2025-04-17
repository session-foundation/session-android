package org.thoughtcrime.securesms.groups

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.groups.compose.GroupMembersScreen

@AndroidEntryPoint
class GroupMembersActivity: FullComposeScreenLockActivity() {

    companion object {
        private const val EXTRA_GROUP_ID = "GroupMembersActivity_groupID"

        fun createIntent(context: Context, groupSessionId: String): Intent {
            return Intent(context, GroupMembersActivity::class.java).apply {
                putExtra(EXTRA_GROUP_ID, groupSessionId)
            }
        }
    }


    @Composable
    override fun ComposeContent() {
        GroupMembersScreen (
            groupId = AccountId(intent.getStringExtra(EXTRA_GROUP_ID)!!),
            onBack = this::finish
        )
    }
}

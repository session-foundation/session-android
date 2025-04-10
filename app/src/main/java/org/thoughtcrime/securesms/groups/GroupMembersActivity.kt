package org.thoughtcrime.securesms.groups

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.FullComposeActivity
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.groups.compose.GroupMembersScreen
import org.thoughtcrime.securesms.ui.setComposeContent
import org.thoughtcrime.securesms.ui.theme.SessionMaterialTheme

@AndroidEntryPoint
class GroupMembersActivity: FullComposeActivity() {

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

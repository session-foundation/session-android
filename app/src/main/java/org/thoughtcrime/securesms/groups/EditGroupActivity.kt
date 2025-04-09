package org.thoughtcrime.securesms.groups

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.FullComposeActivity
import org.thoughtcrime.securesms.groups.compose.EditGroupScreen

@AndroidEntryPoint
class EditGroupActivity: FullComposeActivity() {

    companion object {
        private const val EXTRA_GROUP_ID = "EditClosedGroupActivity_groupID"

        fun createIntent(context: Context, groupSessionId: String): Intent {
            return Intent(context, EditGroupActivity::class.java).apply {
                putExtra(EXTRA_GROUP_ID, groupSessionId)
            }
        }
    }

    @Composable
    override fun ComposeContent() {
        EditGroupScreen(
            groupId = AccountId(intent.getStringExtra(EXTRA_GROUP_ID)!!),
            onBack = this::finish
        )
    }
}
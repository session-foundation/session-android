package org.thoughtcrime.securesms.groups

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.groups.compose.EditGroupScreen
import org.thoughtcrime.securesms.groups.compose.GroupMembersScreen
import org.thoughtcrime.securesms.ui.theme.SessionMaterialTheme

@AndroidEntryPoint
class GroupMembersActivity: PassphraseRequiredActionBarActivity() {

    companion object {
        private const val EXTRA_GROUP_ID = "GroupMembersActivity_groupID"

        fun createIntent(context: Context, groupSessionId: String): Intent {
            return Intent(context, GroupMembersActivity::class.java).apply {
                putExtra(EXTRA_GROUP_ID, groupSessionId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        setContent {
            SessionMaterialTheme {
                GroupMembersScreen (
                    groupId = AccountId(intent.getStringExtra(EXTRA_GROUP_ID)!!),
                    onBack = this::finish
                )
            }
        }
    }
}
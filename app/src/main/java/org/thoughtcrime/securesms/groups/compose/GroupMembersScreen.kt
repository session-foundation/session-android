package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.groups.GroupMemberState
import org.thoughtcrime.securesms.groups.GroupMembersViewModel
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.PreviewTheme

@Composable
fun GroupMembersScreen(
    groupId: AccountId,
    onBack: () -> Unit,
) {
    val viewModel = hiltViewModel<GroupMembersViewModel, GroupMembersViewModel.Factory> { factory ->
        factory.create(groupId)
    }

    GroupMembers(
        onBack = onBack,
        members = viewModel.members.collectAsState().value
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMembers(
    onBack: () -> Unit,
    members: List<GroupMemberState>,
) {

    Scaffold(
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.groupMembers),
                onBack = onBack,
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            // List of members
            LazyColumn(modifier = Modifier) {
                items(members) { member ->
                    // Each member's view
                    MemberItem(
                        accountId = member.accountId,
                        title = member.name,
                        subtitle = member.statusLabel,
                        subtitleColor = if (member.highlightStatus) {
                            LocalColors.current.danger
                        } else {
                            LocalColors.current.textSecondary
                        },
                        showAsAdmin = member.showAsAdmin
                    )
                }
            }
        }
    }

}

@Preview
@Composable
private fun EditGroupPreview() {
    PreviewTheme {
        val oneMember = GroupMemberState(
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"),
            name = "Test User",
            status = GroupMember.Status.INVITE_SENT,
            highlightStatus = false,
            canPromote = true,
            canRemove = true,
            canResendInvite = false,
            canResendPromotion = false,
            showAsAdmin = false,
            clickable = true,
            statusLabel = "Invited"
        )
        val twoMember = GroupMemberState(
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1235"),
            name = "Test User 2",
            status = GroupMember.Status.PROMOTION_FAILED,
            highlightStatus = true,
            canPromote = true,
            canRemove = true,
            canResendInvite = false,
            canResendPromotion = false,
            showAsAdmin = true,
            clickable = true,
            statusLabel = "Promotion failed"
        )
        val threeMember = GroupMemberState(
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1236"),
            name = "Test User 3",
            status = null,
            highlightStatus = false,
            canPromote = true,
            canRemove = true,
            canResendInvite = false,
            canResendPromotion = false,
            showAsAdmin = false,
            clickable = true,
            statusLabel = ""
        )

        GroupMembers(
            onBack = {},
            members = listOf(oneMember, twoMember, threeMember),
        )
    }
}

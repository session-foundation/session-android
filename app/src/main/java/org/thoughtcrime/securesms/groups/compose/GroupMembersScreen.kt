package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.groups.GroupMemberState
import org.thoughtcrime.securesms.groups.GroupMembersViewModel
import org.thoughtcrime.securesms.ui.ObserveAsEvents
import org.thoughtcrime.securesms.ui.SearchBar
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement

@Composable
fun GroupMembersScreen(
    viewModel: GroupMembersViewModel,
    onBack: () -> Unit,
) {

    val context = LocalContext.current
    ObserveAsEvents(flow = viewModel.navigationActions) { intent ->
        context.startActivity(intent)
    }

    GroupMembers(
        onBack = onBack,
        members = viewModel.members.collectAsState().value,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSearchQueryClear = {viewModel.onSearchQueryChanged("") },
        onMemberClicked = viewModel::onMemberClicked
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMembers(
    onBack: () -> Unit,
    members: List<GroupMemberState>,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onSearchQueryClear: () -> Unit,
    onMemberClicked: (AccountId) -> Unit,
) {
    Scaffold(
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.groupMembers),
                onBack = onBack,
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
        ) {
            SearchBar(
                query = searchQuery,
                onValueChanged = onSearchQueryChanged,
                onClear = onSearchQueryClear,
                placeholder = stringResource(R.string.searchContacts),
                modifier = Modifier
                    .padding(horizontal = LocalDimensions.current.smallSpacing)
                    .qaTag(R.string.AccessibilityId_groupNameSearch),
                backgroundColor = LocalColors.current.backgroundSecondary,
            )

            // List of members
            LazyColumn() {
                items(members) { member ->
                    // Each member's view
                    MemberItem(
                        address = Address.fromSerialized(member.accountId.hexString),
                        onClick = { onMemberClicked(member.accountId) },
                        title = member.name,
                        subtitle = member.statusLabel,
                        subtitleColor = if (member.highlightStatus) {
                            LocalColors.current.danger
                        } else {
                            LocalColors.current.textSecondary
                        },
                        showAsAdmin = member.showAsAdmin,
                        showProBadge = member.showProBadge,
                        avatarUIData = member.avatarUIData
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
            showProBadge = true,
            clickable = true,
            statusLabel = "Invited",
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
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
            showProBadge = true,
            clickable = true,
            statusLabel = "Promotion failed",
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
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
            showProBadge = false,
            clickable = true,
            statusLabel = "",
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
        )

        GroupMembers(
            onBack = {},
            members = listOf(oneMember, twoMember, threeMember),
            searchQuery = "",
            onSearchQueryChanged = {},
            onSearchQueryClear = {},
            onMemberClicked = {},
        )
    }
}

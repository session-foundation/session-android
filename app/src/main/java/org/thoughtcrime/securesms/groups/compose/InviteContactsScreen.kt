package org.thoughtcrime.securesms.groups.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.groups.ContactItem
import org.thoughtcrime.securesms.groups.SelectContactsViewModel
import org.thoughtcrime.securesms.ui.BottomFadingEdgeBox
import org.thoughtcrime.securesms.ui.SearchBar
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.PrimaryOutlineButton
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement


@Composable
fun InviteContactsScreen(
    viewModel: SelectContactsViewModel,
    onDoneClicked: () -> Unit,
    onBack: () -> Unit,
    banner: @Composable ()->Unit = {}
) {
    InviteContacts(
        contacts = viewModel.contacts.collectAsState().value,
        onContactItemClicked = viewModel::onContactItemClicked,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSearchQueryClear = {viewModel.onSearchQueryChanged("") },
        onDoneClicked = onDoneClicked,
        onBack = onBack,
        banner = banner
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteContacts(
    contacts: List<ContactItem>,
    onContactItemClicked: (accountId: AccountId) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onSearchQueryClear: () -> Unit,
    onDoneClicked: () -> Unit,
    onBack: () -> Unit,
    banner: @Composable ()->Unit = {}
) {
    Scaffold(
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.membersInvite),
                onBack = onBack,
            )
        },
    ) { paddings ->
        Column(
            modifier = Modifier
                .padding(paddings)
                .consumeWindowInsets(paddings),
        ) {
            banner()

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

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

            val scrollState = rememberLazyListState()

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            BottomFadingEdgeBox(modifier = Modifier.weight(1f)) { bottomContentPadding ->
                if(contacts.isEmpty() && searchQuery.isEmpty()){
                    Text(
                        text = stringResource(id = R.string.contactNone),
                        modifier = Modifier.padding(top = LocalDimensions.current.spacing)
                            .align(Alignment.TopCenter),
                        style = LocalType.current.base.copy(color = LocalColors.current.textSecondary)
                    )
                } else {
                    LazyColumn(
                        state = scrollState,
                        contentPadding = PaddingValues(bottom = bottomContentPadding),
                    ) {
                        multiSelectMemberList(
                            contacts = contacts,
                            onContactItemClicked = onContactItemClicked,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                PrimaryOutlineButton(
                    onClick = onDoneClicked,
                    modifier = Modifier
                        .padding(vertical = LocalDimensions.current.spacing)
                        .qaTag(R.string.qa_invite_button),
                ) {
                    Text(
                        stringResource(id = R.string.membersInviteTitle)
                    )
                }
            }
        }

    }
}

@Preview
@Composable
private fun PreviewSelectContacts() {
    val random = "05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"
    val contacts = List(20) {
        ContactItem(
            accountID = AccountId(random),
            name = "User $it",
            selected = it % 3 == 0,
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
        )
    }

    PreviewTheme {
        InviteContacts(
            contacts = contacts,
            onContactItemClicked = {},
            searchQuery = "",
            onSearchQueryChanged = {},
            onSearchQueryClear = {},
            onDoneClicked = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PreviewSelectEmptyContacts() {
    val contacts = emptyList<ContactItem>()

    PreviewTheme {
        InviteContacts(
            contacts = contacts,
            onContactItemClicked = {},
            searchQuery = "",
            onSearchQueryChanged = {},
            onSearchQueryClear = {},
            onDoneClicked = {},
            onBack = {},
            banner = { GroupMinimumVersionBanner() }
        )
    }
}

@Preview
@Composable
private fun PreviewSelectEmptyContactsWithSearch() {
    val contacts = emptyList<ContactItem>()

    PreviewTheme {
        InviteContacts(
            contacts = contacts,
            onContactItemClicked = {},
            searchQuery = "Test",
            onSearchQueryChanged = {},
            onSearchQueryClear = {},
            onDoneClicked = {},
            onBack = {},
            banner = { GroupMinimumVersionBanner() }
        )
    }
}


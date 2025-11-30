package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.groups.ContactItem
import org.thoughtcrime.securesms.groups.SelectContactsViewModel
import org.thoughtcrime.securesms.ui.BottomFadingEdgeBox
import org.thoughtcrime.securesms.ui.CollapsibleFooterAction
import org.thoughtcrime.securesms.ui.CollapsibleFooterActionData
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.SearchBar
import org.thoughtcrime.securesms.ui.components.BackAppBar
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
    banner: @Composable () -> Unit = {}
) {
    val footerData by viewModel.collapsibleFooterState.collectAsState()

    InviteContacts(
        contacts = viewModel.contacts.collectAsState().value,
        onContactItemClicked = viewModel::onContactItemClicked,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSearchQueryClear = { viewModel.onSearchQueryChanged("") },
        onDoneClicked = onDoneClicked,
        onBack = onBack,
        banner = banner,
        data = footerData,
        onToggleFooter = viewModel::toggleFooter,
        onCloseFooter = viewModel::clearSelection

    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteContacts(
    contacts: List<ContactItem>,
    onContactItemClicked: (address: Address) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onSearchQueryClear: () -> Unit,
    onDoneClicked: () -> Unit,
    onBack: () -> Unit,
    banner: @Composable () -> Unit = {},
    data: SelectContactsViewModel.CollapsibleFooterState,
    onToggleFooter: () -> Unit,
    onCloseFooter: () -> Unit,
) {
    val colors = LocalColors.current
    val trayItems = listOf(
        CollapsibleFooterItemData(
            label = GetString(LocalResources.current.getString(R.string.membersInvite)),
            buttonLabel = GetString(LocalResources.current.getString(R.string.membersInviteTitle)),
            buttonColor = colors.accent,
            onClick = { onDoneClicked() }
        )
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.membersInvite),
                onBack = onBack,
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .imePadding()
            ) {
                CollapsibleFooterAction(
                    data = CollapsibleFooterActionData(
                        title = data.footerActionTitle,
                        collapsed = data.collapsed,
                        visible = data.visible,
                        items = trayItems
                    ),
                    onCollapsedClicked = onToggleFooter,
                    onClosedClicked = onCloseFooter
                )
            }
        }
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

            Box(modifier = Modifier.weight(1f)) {
                if (contacts.isEmpty() && searchQuery.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.contactNone),
                        modifier = Modifier
                            .padding(top = LocalDimensions.current.spacing)
                            .align(Alignment.TopCenter),
                        style = LocalType.current.base.copy(color = LocalColors.current.textSecondary)
                    )
                } else {
                    LazyColumn(
                        state = scrollState,
                        contentPadding = PaddingValues(bottom = LocalDimensions.current.spacing),
                    ) {
                        multiSelectMemberList(
                            contacts = contacts,
                            onContactItemClicked = onContactItemClicked,
                        )
                    }
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
            address = Address.fromSerialized(random),
            name = "User $it",
            selected = it % 3 == 0,
            showProBadge = true,
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
            data = SelectContactsViewModel.CollapsibleFooterState(
                collapsed = false,
                visible = true,
                footerActionTitle = GetString("1 Contact Selected")
            ),
            onToggleFooter = { },
            onCloseFooter = { },
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
            data = SelectContactsViewModel.CollapsibleFooterState(
                collapsed = true,
                visible = false,
                footerActionTitle = GetString("")
            ),
            onToggleFooter = { },
            onCloseFooter = { }
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
            data = SelectContactsViewModel.CollapsibleFooterState(
                collapsed = true,
                visible = false,
                footerActionTitle = GetString("")
            ),
            onToggleFooter = { },
            onCloseFooter = { }
        )
    }
}


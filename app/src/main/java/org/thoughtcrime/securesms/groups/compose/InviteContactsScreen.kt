package org.thoughtcrime.securesms.groups.compose

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.groups.ContactItem
import org.thoughtcrime.securesms.groups.InviteMembersViewModel
import org.thoughtcrime.securesms.groups.InviteMembersViewModel.Commands.CloseFooter
import org.thoughtcrime.securesms.groups.InviteMembersViewModel.Commands.ContactItemClick
import org.thoughtcrime.securesms.groups.InviteMembersViewModel.Commands.RemoveSearchState
import org.thoughtcrime.securesms.groups.InviteMembersViewModel.Commands.SearchFocusChange
import org.thoughtcrime.securesms.groups.InviteMembersViewModel.Commands.SearchQueryChange
import org.thoughtcrime.securesms.groups.InviteMembersViewModel.Commands.ShowSendInviteDialog
import org.thoughtcrime.securesms.groups.InviteMembersViewModel.Commands.ToggleFooter
import org.thoughtcrime.securesms.ui.CollapsibleFooterAction
import org.thoughtcrime.securesms.ui.CollapsibleFooterActionData
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.SearchBarWithClose
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
    viewModel: InviteMembersViewModel,
    onDoneClicked: (shareHistory: Boolean) -> Unit,
    onBack: () -> Unit,
    banner: @Composable () -> Unit = {},
    forCommunity: Boolean = false,
) {
    InviteContacts(
        contacts = viewModel.contacts.collectAsState().value,
        uiState = viewModel.uiState.collectAsState().value,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        hasContacts = viewModel.hasContacts.collectAsState().value,
        onDoneClicked = onDoneClicked,
        onBack = onBack,
        banner = banner,
        sendCommand = viewModel::sendCommand,
        forCommunity = forCommunity
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteContacts(
    contacts: List<ContactItem>,
    uiState: InviteMembersViewModel.UiState,
    searchQuery: String,
    hasContacts: Boolean,
    onDoneClicked: (shareHistory: Boolean) -> Unit,
    onBack: () -> Unit,
    banner: @Composable () -> Unit = {},
    sendCommand: (command: InviteMembersViewModel.Commands) -> Unit,
    forCommunity: Boolean = false
) {

    val trayItems = listOf(
        CollapsibleFooterItemData(
            label = GetString(LocalResources.current.getString(R.string.membersInvite)),
            buttonLabel = GetString(LocalResources.current.getString(R.string.membersInviteTitle)),
            isDanger = false,
            onClick = {
                if (forCommunity) onDoneClicked(false) // Community does not need the dialog
                else sendCommand(ShowSendInviteDialog)
            }
        )
    )

    val handleBack: () -> Unit = {
        when {
            uiState.isSearchFocused -> sendCommand(RemoveSearchState(false))
            else -> onBack()
        }
    }

    // Intercept system back
    BackHandler(enabled = true) { handleBack() }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.membersInvite),
                onBack = handleBack,
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
                        title = uiState.footer.footerActionTitle,
                        collapsed = uiState.footer.collapsed,
                        visible = uiState.footer.visible,
                        items = trayItems
                    ),
                    onCollapsedClicked = { sendCommand(ToggleFooter) },
                    onClosedClicked = { sendCommand(CloseFooter) }
                )
            }
        }
    ) { paddings ->
        Column(
            modifier = Modifier
                .padding(paddings)
                .consumeWindowInsets(paddings),
        ) {
            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            if (hasContacts) {
                SearchBarWithClose(
                    query = searchQuery,
                    onValueChanged = { query -> sendCommand(SearchQueryChange(query)) },
                    onClear = { sendCommand(SearchQueryChange("")) },
                    placeholder = stringResource(R.string.searchContacts),
                    modifier = Modifier
                        .padding(horizontal = LocalDimensions.current.smallSpacing)
                        .qaTag(R.string.AccessibilityId_groupNameSearch),
                    backgroundColor = LocalColors.current.backgroundSecondary,
                    isFocused = uiState.isSearchFocused,
                    onFocusChanged = { isFocused -> sendCommand(SearchFocusChange(isFocused)) },
                    enabled = true,
                )

                Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
            }

            val scrollState = rememberLazyListState()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (!hasContacts && searchQuery.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.membersInviteNoContacts),
                        modifier = Modifier
                            .align(Alignment.TopCenter),
                        textAlign = TextAlign.Center,
                        style = LocalType.current.base.copy(color = LocalColors.current.textSecondary)
                    )
                } else {
                    LazyColumn(
                        state = scrollState,
                        contentPadding = PaddingValues(bottom = LocalDimensions.current.spacing),
                    ) {
                        multiSelectMemberList(
                            contacts = contacts,
                            onContactItemClicked = { address -> sendCommand(ContactItemClick(address)) },
                        )
                    }
                }
            }
        }
    }

    if (uiState.inviteContactsDialog.visible) {
        InviteMembersDialog(
            state = uiState.inviteContactsDialog,
            onInviteClicked = onDoneClicked,
            onDismiss = { }
        )
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
            onDoneClicked = {},
            onBack = {},
            banner = {},
            sendCommand = {},
            uiState = InviteMembersViewModel.UiState(
                footer = InviteMembersViewModel.CollapsibleFooterState(
                    collapsed = false,
                    visible = true,
                    footerActionTitle = GetString("1 Contact Selected")
                )
            ),
            searchQuery = "",
            hasContacts = true
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
            onDoneClicked = {},
            onBack = {},
            banner = {},
            sendCommand = {},
            uiState = InviteMembersViewModel.UiState(
                footer = InviteMembersViewModel.CollapsibleFooterState(
                    collapsed = true,
                    visible = false,
                    footerActionTitle = GetString("")
                )
            ),
            searchQuery = "Test",
            hasContacts = false
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
            onDoneClicked = {},
            onBack = {},
            banner = {},
            sendCommand = {},
            uiState = InviteMembersViewModel.UiState(
                footer = InviteMembersViewModel.CollapsibleFooterState(
                    collapsed = true,
                    visible = false,
                    footerActionTitle = GetString("")
                )
            ),
            searchQuery = "",
            hasContacts = false
        )
    }
}


package org.thoughtcrime.securesms.preferences

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.groups.ContactItem
import org.thoughtcrime.securesms.groups.compose.multiSelectMemberList
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.BottomFadingEdgeBox
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.SearchBar
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.OutlineButton
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement


@Composable
fun BlockedContactsScreen(
    viewModel: BlockedContactsViewModel,
    onBack: () -> Unit,
) {
    val hasContacts by viewModel.hasContacts.collectAsState()
    BlockedContacts(
        contacts = viewModel.contacts.collectAsState().value,
        hasContacts = hasContacts,
        onContactItemClicked = viewModel::onContactItemClicked,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSearchQueryClear = {viewModel.onSearchQueryChanged("") },
        onDoneClicked = viewModel::onUnblockClicked,
        onBack = onBack,
    )

    // dialogs
    val showConfirmDialog by viewModel.unblockDialog.collectAsState()

    if(showConfirmDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideUnblockDialog,
            title = annotatedStringResource(R.string.blockUnblock),
            text = annotatedStringResource(viewModel.getDialogText()),
            buttons = listOf(
                DialogButtonData(
                    GetString(R.string.blockUnblock),
                    color = LocalColors.current.danger,
                    onClick = viewModel::unblock
                ),
                DialogButtonData(GetString(android.R.string.cancel), dismissOnClick = true)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedContacts(
    contacts: List<ContactItem>,
    hasContacts: Boolean,
    onContactItemClicked: (address: Address) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onSearchQueryClear: () -> Unit,
    onDoneClicked: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.conversationsBlockedContacts),
                onBack = onBack,
            )
        },
    ) { paddings ->
        Column(
            modifier = Modifier
                .padding(paddings)
                .consumeWindowInsets(paddings),
        ) {
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
                if(!hasContacts){
                    Text(
                        text = stringResource(id = R.string.blockBlockedNone),
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
                OutlineButton(
                    onClick = onDoneClicked,
                    color = LocalColors.current.danger,
                    enabled = contacts.any { it.selected },
                    modifier = Modifier
                        .padding(vertical = LocalDimensions.current.spacing)
                        .qaTag(R.string.qa_unblock_button),
                ) {
                    Text(
                        stringResource(id = R.string.blockUnblock)
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
        BlockedContacts(
            contacts = contacts,
            hasContacts = true,
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
        BlockedContacts(
            contacts = contacts,
            hasContacts = false,
            onContactItemClicked = {},
            searchQuery = "",
            onSearchQueryChanged = {},
            onSearchQueryClear = {},
            onDoneClicked = {},
            onBack = {},
        )
    }
}



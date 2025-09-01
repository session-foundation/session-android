package org.thoughtcrime.securesms

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.thoughtcrime.securesms.groups.compose.MemberItem
import org.thoughtcrime.securesms.ui.SearchBar
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.CircularProgressIndicator
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement


@Composable
fun ShareScreen(
    viewModel: ShareViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val hasConversations by viewModel.hasAnyConversations.collectAsState()

    ShareList(
        state = state,
        contacts = viewModel.contacts.collectAsState().value,
        hasConversations = hasConversations,
        onContactItemClicked = viewModel::onContactItemClicked,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSearchQueryClear = {viewModel.onSearchQueryChanged("") },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareList(
    state: ShareViewModel.UIState,
    contacts: List<ConversationItem>,
    hasConversations: Boolean,
    onContactItemClicked: (address: Address) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onSearchQueryClear: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            BackAppBar(
                title = Phrase.from(LocalContext.current, R.string.shareToSession)
                    .put(APP_NAME_KEY, stringResource(R.string.app_name))
                    .format().toString(),
                onBack = onBack,
            )
        },
    ) { paddings ->
        Crossfade(state.showLoader) { showLoader ->
            if (showLoader) {
                Box(
                    modifier = Modifier.fillMaxSize()
                            .padding(top = paddings.calculateTopPadding())
                            .consumeWindowInsets(paddings),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .padding(top = paddings.calculateTopPadding())
                        .consumeWindowInsets(paddings),
                ) {
                    Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

                    SearchBar(
                        query = searchQuery,
                        onValueChanged = onSearchQueryChanged,
                        onClear = onSearchQueryClear,
                        placeholder = stringResource(R.string.search),
                        modifier = Modifier
                            .padding(horizontal = LocalDimensions.current.smallSpacing)
                            .qaTag(R.string.AccessibilityId_groupNameSearch),
                        backgroundColor = LocalColors.current.backgroundSecondary,
                    )

                    val scrollState = rememberLazyListState()

                    Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

                    if (!hasConversations) {
                        Text(
                            text = stringResource(id = R.string.conversationsNone),
                            modifier = Modifier.padding(top = LocalDimensions.current.spacing)
                                .align(Alignment.CenterHorizontally),
                            style = LocalType.current.base.copy(color = LocalColors.current.textSecondary)
                        )
                    } else {
                        LazyColumn(
                            state = scrollState,
                            contentPadding = PaddingValues(bottom = paddings.calculateBottomPadding()),
                        ) {

                            items(contacts) { contacts ->
                                // Each member's view
                                MemberItem(
                                    address = contacts.address,
                                    onClick = onContactItemClicked,
                                    title = contacts.name,
                                    showProBadge = contacts.showProBadge,
                                    showAsAdmin = false,
                                    avatarUIData = contacts.avatarUIData
                                )
                            }
                        }
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
        ConversationItem(
            address = Address.fromSerialized(random),
            name = "User $it",
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
        ShareList(
            state = ShareViewModel.UIState(false),
            contacts = contacts,
            hasConversations = true,
            onContactItemClicked = {},
            searchQuery = "",
            onSearchQueryChanged = {},
            onSearchQueryClear = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PreviewSelectEmptyContacts() {
    val contacts = emptyList<ConversationItem>()

    PreviewTheme {
        ShareList(
            state = ShareViewModel.UIState(false),
            contacts = contacts,
            hasConversations = false,
            onContactItemClicked = {},
            searchQuery = "",
            onSearchQueryChanged = {},
            onSearchQueryClear = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PreviewSelectEmptyContactsWithSearch() {
    val contacts = emptyList<ConversationItem>()

    PreviewTheme {
        ShareList(
            state = ShareViewModel.UIState(true),
            contacts = contacts,
            hasConversations = false,
            onContactItemClicked = {},
            searchQuery = "Test",
            onSearchQueryChanged = {},
            onSearchQueryClear = {},
            onBack = {},
        )
    }
}


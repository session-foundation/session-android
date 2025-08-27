package org.thoughtcrime.securesms.home.startconversation.group

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.groups.ContactItem
import org.thoughtcrime.securesms.groups.compose.GroupMinimumVersionBanner
import org.thoughtcrime.securesms.groups.compose.multiSelectMemberList
import org.thoughtcrime.securesms.ui.BottomFadingEdgeBox
import org.thoughtcrime.securesms.ui.LoadingArcOr
import org.thoughtcrime.securesms.ui.SearchBar
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.SessionOutlinedTextField
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement


@Composable
fun CreateGroupScreen(
    fromLegacyGroupId: String?,
    onNavigateToConversationScreen: (address: Address.Conversable) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val viewModel = hiltViewModel<CreateGroupViewModel, CreateGroupViewModel.Factory> { factory ->
        factory.create(fromLegacyGroupId)
    }

    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CreateGroupEvent.NavigateToConversation -> {
                    onClose()
                    onNavigateToConversationScreen(event.address)
                }

                is CreateGroupEvent.Error -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    CreateGroup(
        groupName = viewModel.groupName.collectAsState().value,
        onGroupNameChanged = viewModel::onGroupNameChanged,
        groupNameError = viewModel.groupNameError.collectAsState().value,
        contactSearchQuery = viewModel.searchQuery.collectAsState().value,
        onContactSearchQueryChanged = viewModel::onSearchQueryChanged,
        onContactSearchQueryClear = { viewModel.onSearchQueryChanged("") },
        onContactItemClicked = viewModel::onContactItemClicked,
        showLoading = viewModel.isLoading.collectAsState().value,
        items = viewModel.contacts.collectAsState().value,
        onCreateClicked = viewModel::onCreateClicked,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroup(
    groupName: String,
    onGroupNameChanged: (String) -> Unit,
    groupNameError: String,
    contactSearchQuery: String,
    onContactSearchQueryChanged: (String) -> Unit,
    onContactSearchQueryClear: () -> Unit,
    onContactItemClicked: (address: Address) -> Unit,
    showLoading: Boolean,
    items: List<ContactItem>,
    onCreateClicked: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    Scaffold(
        containerColor = LocalColors.current.backgroundSecondary,
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.groupCreate),
                backgroundColor = LocalColors.current.backgroundSecondary,
                onBack = onBack,
                windowInsets = WindowInsets(0, 0, 0, 0), // Insets handled by the dialog
            )
        },
    ) { paddings ->
        Column(
            modifier = modifier.padding(paddings).consumeWindowInsets(paddings),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GroupMinimumVersionBanner()

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            SessionOutlinedTextField(
                text = groupName,
                onChange = onGroupNameChanged,
                placeholder = stringResource(R.string.groupNameEnter),
                textStyle = LocalType.current.base,
                modifier = Modifier.padding(horizontal = LocalDimensions.current.spacing)
                    .qaTag(R.string.AccessibilityId_groupNameEnter),
                error = groupNameError.takeIf { it.isNotBlank() },
                enabled = !showLoading,
                innerPadding = PaddingValues(LocalDimensions.current.smallSpacing),
                onContinue = focusManager::clearFocus
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            SearchBar(
                query = contactSearchQuery,
                onValueChanged = onContactSearchQueryChanged,
                onClear = onContactSearchQueryClear,
                placeholder = stringResource(R.string.searchContacts),
                modifier = Modifier.padding(horizontal = LocalDimensions.current.spacing)
                    .qaTag(R.string.AccessibilityId_groupNameSearch),
                enabled = !showLoading
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            BottomFadingEdgeBox(
                modifier = Modifier.weight(1f)
                    .nestedScroll(rememberNestedScrollInteropConnection()),
                fadingColor = LocalColors.current.backgroundSecondary
            ) { bottomContentPadding ->
                if(items.isEmpty() && contactSearchQuery.isEmpty()){
                    Text(
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = LocalDimensions.current.xsSpacing),
                        text = stringResource(R.string.contactNone),
                        textAlign = TextAlign.Center,
                        style = LocalType.current.base.copy(color = LocalColors.current.textSecondary)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = bottomContentPadding)
                    ) {
                        multiSelectMemberList(
                            contacts = items,
                            onContactItemClicked = onContactItemClicked,
                            enabled = !showLoading
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

            AccentOutlineButton(
                onClick = onCreateClicked,
                modifier = Modifier
                    .padding(horizontal = LocalDimensions.current.spacing)
                    .qaTag(R.string.AccessibilityId_groupCreate)
            ) {
                LoadingArcOr(loading = showLoading) {
                    Text(stringResource(R.string.create))
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
        }

    }

}

@Preview
@Composable
private fun CreateGroupPreview(
) {
    val random = "05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"
    val previewMembers = listOf(
        ContactItem(address = Address.fromSerialized(random), name = "Alice", selected = false,
            showProBadge = true,
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
        ),
        ContactItem(address = Address.fromSerialized(random), name = "Bob", selected = true,
            showProBadge = false,
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
        ),
    )

    PreviewTheme {
        CreateGroup(
            groupName = "",
            onGroupNameChanged = {},
            groupNameError = "",
            contactSearchQuery = "",
            onContactSearchQueryChanged = {},
            onContactSearchQueryClear = {},
            onContactItemClicked = {},
            showLoading = false,
            items = previewMembers,
            onCreateClicked = {},
            onBack = {},
            modifier = Modifier.background(LocalColors.current.backgroundSecondary),
        )
    }

}

@Preview
@Composable
private fun CreateEmptyGroupPreview(
) {
    val previewMembers = emptyList<ContactItem>()

    PreviewTheme {
        CreateGroup(
            groupName = "",
            onGroupNameChanged = {},
            groupNameError = "",
            contactSearchQuery = "",
            onContactSearchQueryChanged = {},
            onContactSearchQueryClear = {},
            onContactItemClicked = {},
            showLoading = false,
            items = previewMembers,
            onCreateClicked = {},
            onBack = {},
            modifier = Modifier.background(LocalColors.current.backgroundSecondary),
        )
    }
}

@Preview
@Composable
private fun CreateEmptyGroupPreviewWithSearch(
) {
    val previewMembers = emptyList<ContactItem>()

    PreviewTheme {
        CreateGroup(
            groupName = "",
            onGroupNameChanged = {},
            groupNameError = "",
            contactSearchQuery = "Test",
            onContactSearchQueryChanged = {},
            onContactSearchQueryClear = {},
            onContactItemClicked = {},
            showLoading = false,
            items = previewMembers,
            onCreateClicked = {},
            onBack = {},
            modifier = Modifier.background(LocalColors.current.backgroundSecondary),
        )
    }
}


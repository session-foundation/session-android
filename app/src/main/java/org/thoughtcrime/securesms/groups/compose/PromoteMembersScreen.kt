package org.thoughtcrime.securesms.groups.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import network.loki.messenger.R
import org.thoughtcrime.securesms.groups.GroupMemberState
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands.CloseFooter
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands.DismissConfirmDialog
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands.DismissPromoteDialog
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands.MemberClick
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands.SearchFocusChange
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands.SearchQueryChange
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands.ShowConfirmDialog
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands.ShowPromoteDialog
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel.Commands.ToggleFooter
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.CollapsibleFooterAction
import org.thoughtcrime.securesms.ui.CollapsibleFooterActionData
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.adaptive.getAdaptiveInfo
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType

@Composable
fun PromoteMembersScreen(
    viewModel: PromoteMembersViewModel,
    onBack: () -> Unit,
    onPromoteClicked: (Set<GroupMemberState>) -> Unit
) {
    val uiState = viewModel.uiState.collectAsState().value
    val searchQuery = viewModel.searchQuery.collectAsState().value
    val hasActiveMembers = viewModel.hasActiveMembers.collectAsState().value
    val members = viewModel.activeMembers.collectAsState().value
    val selectedMembers = viewModel.selectedMembers.collectAsState().value

    PromoteMembers(
        onBack = onBack,
        uiState = uiState,
        searchQuery = searchQuery,
        sendCommand = viewModel::onCommand,
        members = members,
        selectedMembers = selectedMembers,
        hasActiveMembers = hasActiveMembers,
        onPromoteClicked = onPromoteClicked
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromoteMembers(
    onBack: () -> Unit,
    uiState: PromoteMembersViewModel.UiState,
    searchQuery: String,
    sendCommand: (command: Commands) -> Unit,
    members: List<GroupMemberState>,
    selectedMembers: Set<GroupMemberState> = emptySet(),
    hasActiveMembers: Boolean = false,
    onPromoteClicked: (Set<GroupMemberState>) -> Unit
) {

    val searchFocused = uiState.isSearchFocused
    val isLandscape = getAdaptiveInfo().isLandscape
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val handleBack: () -> Unit = {
        when {
            searchFocused -> sendCommand(Commands.RemoveSearchState(false))
            else -> onBack()
        }
    }

    val searchHeader: @Composable (Modifier) -> Unit = { modifier ->
        MembersSearchHeader(
            searchFocused = searchFocused,
            searchQuery = searchQuery,
            onQueryChange = { sendCommand(SearchQueryChange(it)) },
            onClear = { sendCommand(SearchQueryChange("")) },
            onFocusChanged = { sendCommand(SearchFocusChange(it)) },
            modifier = modifier
        )
    }

    LaunchedEffect(isLandscape, searchFocused) {
        if (isLandscape && searchFocused) {
            scrollBehavior.state.heightOffset = scrollBehavior.state.heightOffsetLimit
        }
    }

    // Intercept system back
    BackHandler(enabled = true) { handleBack() }

    Scaffold(
        modifier = if (isLandscape) {
            Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        } else {
            Modifier
        },
        topBar = {
            BackAppBar(
                title = pluralStringResource(id = R.plurals.promoteMember, 2),
                onBack = handleBack,
                scrollBehavior = if (isLandscape) scrollBehavior else null
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .imePadding()
            ) {
                CollapsibleFooterAction(
                    data = CollapsibleFooterActionData(
                        title = uiState.footer.footerTitle,
                        collapsed = uiState.footer.collapsed,
                        visible = uiState.footer.visible,
                        items = listOf(
                            CollapsibleFooterItemData(
                                label = uiState.footer.footerActionLabel,
                                buttonLabel = GetString(LocalResources.current.getString(R.string.promote)),
                                isDanger = false,
                                onClick = { sendCommand(ShowPromoteDialog) }
                            )
                        )
                    ),
                    onCollapsedClicked = { sendCommand(ToggleFooter) },
                    onClosedClicked = { sendCommand(CloseFooter) }
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            Text(
                modifier = Modifier
                    .padding(horizontal = LocalDimensions.current.mediumSpacing)
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally),
                text = LocalResources.current.getString(if (!hasActiveMembers) R.string.noNonAdminsInGroup else R.string.membersGroupPromotionAcceptInvite),
                textAlign = TextAlign.Center,
                style = LocalType.current.base,
                color = LocalColors.current.textSecondary
            )

            if (hasActiveMembers) {

                if (!isLandscape) {
                    searchHeader(Modifier)
                }

                // List of members
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .imePadding()
                        .then(if (isLandscape) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier),
                ) {
                    stickyHeader { searchHeader(Modifier) }
                    items(members) { member ->
                        // Each member's view
                        ManageMemberItem(
                            modifier = Modifier.fillMaxWidth(),
                            member = member,
                            onClick = { sendCommand(MemberClick(member)) },
                            selected = member in selectedMembers
                        )
                    }

                    item {
                        Spacer(
                            modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars)
                        )
                    }
                }
            }
        }
    }

    if (uiState.showConfirmDialog) {
        ConfirmDialog(
            sendCommand = sendCommand,
            onConfirmClicked = { onPromoteClicked(selectedMembers) })
    }

    if (uiState.showPromoteDialog) {
        PromotionDialog(sendCommand = sendCommand, bodyText = uiState.promoteDialogBody)
    }
}

@Composable
fun ConfirmDialog(
    modifier: Modifier = Modifier,
    onConfirmClicked: () -> Unit,
    sendCommand: (Commands) -> Unit
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            // hide dialog
            sendCommand(DismissConfirmDialog)
        },
        title = annotatedStringResource(R.string.confirmPromotion),
        text = annotatedStringResource(R.string.confirmPromotionDescription),
        buttons = listOf(
            DialogButtonData(
                text = GetString(stringResource(R.string.cancel)),
                onClick = {
                    sendCommand(DismissConfirmDialog)
                }
            ),
            DialogButtonData(
                text = GetString(stringResource(id = R.string.confirm)),
                color = LocalColors.current.danger,
                dismissOnClick = false,
                onClick = {
                    sendCommand(DismissConfirmDialog)
                    onConfirmClicked()
                }
            )
        )
    )
}

@Composable
fun PromotionDialog(
    modifier: Modifier = Modifier,
    sendCommand: (Commands) -> Unit,
    bodyText: String
) {
    AlertDialog(
        onDismissRequest = {
            // hide dialog
            sendCommand(DismissPromoteDialog)
        },
        title = stringResource(R.string.promote),
        text = bodyText,
        showCloseButton = true,
        content = {
            Text(
                modifier = Modifier.padding(horizontal = LocalDimensions.current.smallSpacing),
                text = LocalResources.current.getString(R.string.promoteAdminsWarning),
                style = LocalType.current.small,
                color = LocalColors.current.warning,
                textAlign = TextAlign.Center
            )
        },
        buttons = listOf(
            DialogButtonData(
                text = GetString(stringResource(id = R.string.promote)),
                color = LocalColors.current.danger,
                dismissOnClick = false,
                onClick = {
                    sendCommand(DismissPromoteDialog)
                    sendCommand(ShowConfirmDialog)

                }
            ),
            DialogButtonData(
                text = GetString(stringResource(R.string.cancel)),
                onClick = {
                    sendCommand(DismissPromoteDialog)
                }
            )
        )
    )
}
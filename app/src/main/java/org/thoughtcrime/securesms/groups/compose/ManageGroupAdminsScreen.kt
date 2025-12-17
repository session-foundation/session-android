package org.thoughtcrime.securesms.groups.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import network.loki.messenger.R
import org.thoughtcrime.securesms.groups.GroupMemberState
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel.Commands.CloseFooter
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel.Commands.MemberClick
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel.Commands.RemoveSearchState
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel.Commands.SearchFocusChange
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel.Commands.SearchQueryChange
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel.Commands.SelfClick
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel.Commands.ToggleFooter
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.CollapsibleFooterActionData
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.ItemButton
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.adaptive.getAdaptiveInfo
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.getCellBottomShape
import org.thoughtcrime.securesms.ui.getCellTopShape
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@Composable
fun ManageGroupAdminsScreen(
    viewModel: ManageGroupAdminsViewModel,
    onBack: () -> Unit,
) {
    ManageAdmins(
        onBack = onBack,
        uiState = viewModel.uiState.collectAsState().value,
        admins = viewModel.adminMembers.collectAsState().value,
        selectedMembers = viewModel.selectedAdmins.collectAsState().value,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        sendCommand = viewModel::onCommand,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageAdmins(
    onBack: () -> Unit,
    uiState: ManageGroupAdminsViewModel.UiState,
    searchQuery: String,
    admins: List<GroupMemberState>,
    selectedMembers: Set<GroupMemberState> = emptySet(),
    sendCommand: (command: ManageGroupAdminsViewModel.Commands) -> Unit,
) {

    val searchFocused = uiState.isSearchFocused
    val isLandscape = getAdaptiveInfo().isLandscape

    val handleBack: () -> Unit = {
        when {
            searchFocused -> sendCommand(RemoveSearchState(false))
            else -> onBack()
        }
    }

    val searchLabel: @Composable (Modifier) -> Unit = { modifier ->
        if (!searchFocused) {
            Text(
                modifier = Modifier.padding(
                    start = LocalDimensions.current.mediumSpacing
                ),
                text = LocalResources.current.getString(R.string.admins),
                style = LocalType.current.base,
                color = LocalColors.current.textSecondary
            )
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

    // Intercept system back
    BackHandler(enabled = true) { handleBack() }

    BaseManageGroupScreen(
        title = stringResource(id = R.string.manageAdmins),
        onBack = handleBack,
        enableCollapsingTopBarInLandscape = true,
        bottomBar = {
            CollapsibleFooterBottomBar(
                footer = CollapsibleFooterActionData(
                    title = uiState.footer.footerActionTitle,
                    collapsed = uiState.footer.collapsed,
                    visible = uiState.footer.visible,
                    items = uiState.footer.footerActionItems
                ),
                onToggle = { sendCommand(ToggleFooter) },
                onClose = { sendCommand(CloseFooter) }
            )
        }
    ) { paddingValues, scrollBehavior ->

        LaunchedEffect(isLandscape, searchFocused) {
            if (isLandscape && searchFocused && scrollBehavior != null) {
                scrollBehavior.state.heightOffset = scrollBehavior.state.heightOffsetLimit
            }
        }

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
                text = LocalResources.current.getString(R.string.adminCannotBeDemoted),
                textAlign = TextAlign.Center,
                style = LocalType.current.base,
                color = LocalColors.current.textSecondary
            )

            if (!isLandscape) {
                OptionsBlock(
                    show = !searchFocused,
                    options = uiState.options
                )
                searchLabel(Modifier)
                searchHeader(Modifier)
            }

            // List of members
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
            ) {
                if (isLandscape) {
                    item {
                        OptionsBlock(
                            show = !searchFocused,
                            options = uiState.options
                        )
                    }
                    item { searchLabel(Modifier) }
                    stickyHeader { searchHeader(Modifier) }
                }
                items(admins) { member ->
                    // Each member's view
                    ManageMemberItem(
                        modifier = Modifier.fillMaxWidth(),
                        member = member,
                        onClick = {
                            if (member.isSelf) sendCommand(SelfClick)
                            else sendCommand(MemberClick(member))
                        },
                        selected = member in selectedMembers
                    )
                }
            }
        }
    }

    if (uiState.inProgress) {
        LoadingDialog()
    }
}

@Composable
private fun OptionsBlock(
    show: Boolean,
    options: List<ManageGroupAdminsViewModel.OptionsItem>,
) {
    AnimatedVisibility(
        visible = show,
        enter = fadeIn(animationSpec = tween(150)) +
                expandVertically(
                    animationSpec = tween(200),
                    expandFrom = Alignment.Top
                ),
        exit = fadeOut(animationSpec = tween(150)) +
                shrinkVertically(
                    animationSpec = tween(180),
                    shrinkTowards = Alignment.Top
                )
    ) {
        Column {
            Cell(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(LocalDimensions.current.smallSpacing),
            ) {
                Column {
                    options.forEachIndexed { index, option ->
                        ItemButton(
                            modifier = Modifier.qaTag(option.qaTag),
                            text = annotatedStringResource(option.name),
                            iconRes = option.icon,
                            shape = when (index) {
                                0 -> getCellTopShape()
                                options.lastIndex -> getCellBottomShape()
                                else -> RectangleShape
                            },
                            onClick = option.onClick,
                        )

                        if (index != options.lastIndex) Divider()
                    }
                }
            }
        }
    }

}

@Preview
@Composable
private fun PreviewManageAdmins(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ManageAdmins(
            onBack = {},
            admins = listOf(),
            searchQuery = "",
            selectedMembers = emptySet(),
            sendCommand = {},
            uiState = ManageGroupAdminsViewModel.UiState(
                options = emptyList(),
                footer = ManageGroupAdminsViewModel.CollapsibleFooterState(
                    visible = false,
                    collapsed = true,
                    footerActionTitle = GetString("2 Admins Selected"),
                    footerActionItems = listOf(
                        CollapsibleFooterItemData(
                            label = GetString("Resend"),
                            buttonLabel = GetString("1"),
                            isDanger = false,
                            onClick = {}
                        ),
                        CollapsibleFooterItemData(
                            label = GetString("Remove"),
                            buttonLabel = GetString("1"),
                            isDanger = true,
                            onClick = { }
                        )
                    )
                )),
        )
    }
}

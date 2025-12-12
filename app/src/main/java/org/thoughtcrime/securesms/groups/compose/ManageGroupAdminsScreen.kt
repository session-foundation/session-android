package org.thoughtcrime.securesms.groups.compose

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import network.loki.messenger.R
import org.thoughtcrime.securesms.groups.GroupMemberState
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel.Commands.*
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.CollapsibleFooterAction
import org.thoughtcrime.securesms.ui.CollapsibleFooterActionData
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.ItemButton
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.SearchBarWithClose
import org.thoughtcrime.securesms.ui.components.BackAppBar
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

    val handleBack: () -> Unit = {
        when {
            searchFocused -> sendCommand(RemoveSearchState(false))
            else -> onBack()
        }
    }

    // Intercept system back
    BackHandler(enabled = true) { handleBack() }

    Scaffold(
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.manageAdmins),
                onBack = handleBack,
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
            ) {
                CollapsibleFooterAction(
                    data = CollapsibleFooterActionData(
                        title = uiState.footer.footerActionTitle,
                        collapsed = uiState.footer.collapsed,
                        visible = uiState.footer.visible,
                        items = uiState.footer.footerActionItems
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
                text = LocalResources.current.getString(R.string.adminCannotBeDemoted),
                textAlign = TextAlign.Center,
                style = LocalType.current.base,
                color = LocalColors.current.textSecondary
            )

            AnimatedVisibility(
                // show only when add-members is enabled AND search is not focused
                visible = !searchFocused,
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
                    Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

                    Cell(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LocalDimensions.current.smallSpacing),
                    ) {
                        Column {
                            uiState.options.forEachIndexed { index, option ->
                                ItemButton(
                                    modifier = Modifier.qaTag(option.qaTag),
                                    text = annotatedStringResource(option.name),
                                    iconRes = option.icon,
                                    shape = when (index) {
                                        0 -> getCellTopShape()
                                        uiState.options.lastIndex -> getCellBottomShape()
                                        else -> RectangleShape
                                    },
                                    onClick = option.onClick,
                                )

                                if (index != uiState.options.lastIndex) Divider()
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            if (!searchFocused) {
                Text(
                    modifier = Modifier.padding(
                        start = LocalDimensions.current.mediumSpacing,
                        bottom = LocalDimensions.current.smallSpacing
                    ),
                    text = LocalResources.current.getString(R.string.admins),
                    style = LocalType.current.base,
                    color = LocalColors.current.textSecondary
                )
            }

            SearchBarWithClose(
                query = searchQuery,
                onValueChanged = { query -> sendCommand(SearchQueryChange(query)) },
                onClear = { sendCommand(SearchQueryChange("")) },
                placeholder = if (searchFocused) "" else LocalResources.current.getString(R.string.search),
                enabled = true,
                isFocused = searchFocused,
                modifier = Modifier.padding(horizontal = LocalDimensions.current.smallSpacing),
                onFocusChanged = { isFocused -> sendCommand(SearchFocusChange(isFocused)) }
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // List of members
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
            ) {
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

                item {
                    Spacer(
                        modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars)
                    )
                }
            }
        }
    }

    if (uiState.inProgress) {
        LoadingDialog()
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

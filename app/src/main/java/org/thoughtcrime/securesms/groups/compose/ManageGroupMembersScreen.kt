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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.groups.GroupMemberState
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.CollapsibleFooterState
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.Commands.CloseFooter
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.Commands.DismissRemoveMembersDialog
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.Commands.MemberClick
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.Commands.RemoveMembers
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.Commands.RemoveSearchState
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.Commands.SearchFocusChange
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.Commands.SearchQueryChange
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.Commands.ToggleFooter
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.OptionsItem
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.CollapsibleFooterActionData
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.ItemButton
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.adaptive.getAdaptiveInfo
import org.thoughtcrime.securesms.ui.components.DialogTitledRadioButton
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
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement

@Composable
fun ManageGroupMembersScreen(
    viewModel: ManageGroupMembersViewModel,
    onBack: () -> Unit,
) {
    ManageMembers(
        onBack = onBack,
        uiState = viewModel.uiState.collectAsState().value,
        members = viewModel.nonAdminMembers.collectAsState().value,
        hasMembers = viewModel.hasNonAdminMembers.collectAsState().value,
        selectedMembers = viewModel.selectedMembers.collectAsState().value,
        showAddMembers = viewModel.showAddMembers.collectAsState().value,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        sendCommand = viewModel::onCommand,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageMembers(
    onBack: () -> Unit,
    uiState: ManageGroupMembersViewModel.UiState,
    searchQuery: String,
    members: List<GroupMemberState>,
    hasMembers: Boolean = false,
    selectedMembers: Set<GroupMemberState> = emptySet(),
    showAddMembers: Boolean,
    sendCommand: (command: ManageGroupMembersViewModel.Commands) -> Unit,
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
                text = LocalResources.current.getString(R.string.membersNonAdmins),
                style = LocalType.current.base,
                color = LocalColors.current.textSecondary
            )
        }
    }

    val header: @Composable (Modifier) -> Unit = { modifier ->
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
        title = stringResource(id = R.string.manageMembers),
        onBack = handleBack,
        enableCollapsingTopBarInLandscape = true,
        collapseTopBar = searchFocused,
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
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            // PORTRAIT: options OUTSIDE scroll
            if (!isLandscape) {
                OptionsBlock(
                    show = showAddMembers && !searchFocused,
                    options = uiState.options
                )
            }

            if (hasMembers) {
                if (!isLandscape) {
                    searchLabel(Modifier)
                    header(Modifier)
                }

                // List of members
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    // LANDSCAPE: options INSIDE scroll
                    if (isLandscape) {
                        item(key = "options") {
                            OptionsBlock(
                                show = showAddMembers && !searchFocused,
                                options = uiState.options
                            )
                        }

                        item { searchLabel(Modifier) }
                        stickyHeader {
                            header(Modifier)
                        }
                    }

                    items(members) { member ->
                        // Each member's view
                        ManageMemberItem(
                            modifier = Modifier.fillMaxWidth(),
                            member = member,
                            onClick = { sendCommand(MemberClick(member)) },
                            selected = member in selectedMembers
                        )
                    }
                }
            } else {
                Text(
                    modifier = Modifier
                        .padding(horizontal = LocalDimensions.current.mediumSpacing)
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally),
                    text = LocalResources.current.getString(R.string.noNonAdminsInGroup),
                    textAlign = TextAlign.Center,
                    style = LocalType.current.base,
                    color = LocalColors.current.textSecondary
                )
            }
        }
    }

    if (uiState.removeMembersDialog.visible) {
        RemoveMembersDialog(
            state = uiState.removeMembersDialog,
            sendCommand = sendCommand
        )
    }

    if (uiState.inProgress) {
        LoadingDialog()
    }
}

@Composable
private fun OptionsBlock(
    show: Boolean,
    options: List<OptionsItem>, // use your actual type
) {
    AnimatedVisibility(
        visible = show,
        enter = fadeIn(animationSpec = tween(150)) +
                expandVertically(animationSpec = tween(200), expandFrom = Alignment.Top),
        exit = fadeOut(animationSpec = tween(150)) +
                shrinkVertically(animationSpec = tween(180), shrinkTowards = Alignment.Top)
    ) {
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
                        onClick = option.onClick
                    )
                    if (index != options.lastIndex) Divider()
                }
            }
        }
    }
}

@Composable
fun RemoveMembersDialog(
    state: ManageGroupMembersViewModel.RemoveMembersDialogState,
    modifier: Modifier = Modifier,
    sendCommand: (ManageGroupMembersViewModel.Commands) -> Unit
) {
    var deleteMessages by retain { mutableStateOf(false) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            // hide dialog
            sendCommand(DismissRemoveMembersDialog)
        },
        title = annotatedStringResource(R.string.remove),
        text = annotatedStringResource(state.removeMemberBody),
        content = {
            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(state.removeMemberText),
                    selected = !deleteMessages,
                    qaTag = GetString(R.string.qa_manage_members_dialog_remove_member)
                )
            ) {
                deleteMessages = false
            }

            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(state.removeMessagesText),
                    selected = deleteMessages,
                    qaTag = GetString(R.string.qa_manage_members_dialog_remove_member_messages)
                )
            ) {
                deleteMessages = true
            }
        },
        buttons = listOf(
            DialogButtonData(
                text = GetString(stringResource(id = R.string.remove)),
                color = LocalColors.current.danger,
                dismissOnClick = false,
                onClick = {
                    sendCommand(DismissRemoveMembersDialog)
                    sendCommand(RemoveMembers(deleteMessages))
                }
            ),
            DialogButtonData(
                text = GetString(stringResource(R.string.cancel)),
                onClick = {
                    sendCommand(DismissRemoveMembersDialog)
                }
            )
        )
    )
}

@Preview
@Composable
private fun EditGroupPreviewSheet() {
    val title = GetString("3 Members Selected")

    // build tray items
    val trayItems = listOf(
        CollapsibleFooterItemData(
            label = GetString("Reseaand"),
            buttonLabel = GetString("Resend"),
            isDanger = false,
            onClick = {}
        ),
        CollapsibleFooterItemData(
            label = GetString("Remove"),
            buttonLabel = GetString("Remove"),
            isDanger = true,
            onClick = { }
        )
    )

    PreviewTheme {
        val oneMember = GroupMemberState(
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"),
            name = "Test User",
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
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
            isSelf = false
        )
        val twoMember = GroupMemberState(
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1235"),
            name = "Test User 2",
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
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
            isSelf = false
        )
        val threeMember = GroupMemberState(
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1236"),
            name = "Test User 3",
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
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
            isSelf = true
        )

        val (_, _) = remember { mutableStateOf<String?>(null) }

        ManageMembers(
            onBack = {},
            members = listOf(oneMember, twoMember, threeMember),
            showAddMembers = true,
            searchQuery = "Test",
            selectedMembers = emptySet(),
            sendCommand = {},
            uiState = ManageGroupMembersViewModel.UiState(
                options = emptyList(),
                footer = CollapsibleFooterState(
                    visible = true,
                    collapsed = false,
                    footerActionTitle = title,
                    footerActionItems = trayItems
                )
            ),
            hasMembers = true,
        )
    }
}

@Preview
@Composable
private fun EditGroupEditNamePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        val oneMember = GroupMemberState(
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"),
            name = "Test User",
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
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
            isSelf = false
        )
        val twoMember = GroupMemberState(
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1235"),
            name = "Test User 2",
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
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
            isSelf = false
        )
        val threeMember = GroupMemberState(
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1236"),
            name = "Test User 3",
            avatarUIData = AvatarUIData(
                listOf(
                    AvatarUIElement(
                        name = "TOTO",
                        color = primaryBlue
                    )
                )
            ),
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
            isSelf = false
        )

        ManageMembers(
            onBack = {},
            members = listOf(oneMember, twoMember, threeMember),
            showAddMembers = true,
            searchQuery = "",
            selectedMembers = emptySet(),
            sendCommand = {},
            uiState = ManageGroupMembersViewModel.UiState(
                options = emptyList(),
                footer = CollapsibleFooterState(
                    visible = true,
                    collapsed = false,
                    footerActionTitle = GetString("3 Members Selected"),
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
            hasMembers = true,
        )
    }
}

@Preview
@Composable
private fun EditGroupEmptyPreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ManageMembers(
            onBack = {},
            members = listOf(),
            showAddMembers = true,
            searchQuery = "",
            selectedMembers = emptySet(),
            sendCommand = {},
            uiState = ManageGroupMembersViewModel.UiState(
                options = emptyList(),
                footer = CollapsibleFooterState(
                    visible = false,
                    collapsed = true,
                    footerActionTitle = GetString("3 Members Selected"),
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
            hasMembers = true,
        )
    }
}
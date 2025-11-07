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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.groups.GroupMemberState
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.CollapsibleFooterState
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel.Commands.*
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.CollapsibleFooterAction
import org.thoughtcrime.securesms.ui.CollapsibleFooterActionData
import org.thoughtcrime.securesms.ui.CollapsibleFooterItemData
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.ItemButton
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.SearchBarWithClose
import org.thoughtcrime.securesms.ui.components.BackAppBar
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
    navigateToInviteContact: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    ManageMembers(
        onBack = onBack,
        uiState = viewModel.uiState.collectAsState().value,
        onAddMemberClick = { navigateToInviteContact(viewModel.excludingAccountIDsFromContactSelection) },
        members = viewModel.nonAdminMembers.collectAsState().value,
        hasMembers = viewModel.hasNonAdminMembers.collectAsState().value,
        selectedMembers = viewModel.selectedMembers.collectAsState().value,
        showAddMembers = viewModel.showAddMembers.collectAsState().value,
        showingError = viewModel.error.collectAsState().value,
        showingResend = viewModel.ongoingAction.collectAsState().value,
        showLoading = viewModel.inProgress.collectAsState().value,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        searchFocused = viewModel.searchFocused.collectAsState().value,
        data = viewModel.collapsibleFooterState.collectAsState().value,
        sendCommand = viewModel::onCommand,
        removeMembersData = viewModel.removeMembersState.collectAsState().value,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageMembers(
    onBack: () -> Unit,
    uiState: ManageGroupMembersViewModel.UiState,
    onAddMemberClick: () -> Unit,
    searchFocused: Boolean,
    searchQuery: String,
    data: CollapsibleFooterState,
    members: List<GroupMemberState>,
    hasMembers: Boolean = false,
    selectedMembers: Set<GroupMemberState> = emptySet(),
    showAddMembers: Boolean,
    showingError: String?,
    showingResend: String?,
    showLoading: Boolean,
    sendCommand: (command: ManageGroupMembersViewModel.Commands) -> Unit,
    removeMembersData: ManageGroupMembersViewModel.RemoveMembersState,
) {

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
                title = stringResource(id = R.string.manageMembers),
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
                        title = data.footerActionTitle,
                        collapsed = data.collapsed,
                        visible = data.visible,
                        items = data.footerActionItems
                    ),
                    onCollapsedClicked = {sendCommand(ToggleFooter)},
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

            AnimatedVisibility(
                // show only when add-members is enabled AND search is not focused
                visible = showAddMembers && !searchFocused,
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
                Cell(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(LocalDimensions.current.smallSpacing),
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

            if (hasMembers) {
                if (!searchFocused) {
                    Text(
                        modifier = Modifier.padding(
                            start = LocalDimensions.current.mediumSpacing,
                            bottom = LocalDimensions.current.smallSpacing
                        ),
                        text = LocalResources.current.getString(R.string.membersNonAdmins),
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
            } else {
                Text(
                    modifier = Modifier
                        .padding(horizontal = LocalDimensions.current.mediumSpacing)
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally),
                    text = LocalResources.current.getString(R.string.NoNonAdminsInGroup),
                    textAlign = TextAlign.Center,
                    style = LocalType.current.base,
                    color = LocalColors.current.textSecondary
                )
            }
        }
    }
    
    if(removeMembersData.visible){
        ShowRemoveMembersDialog(
            state = removeMembersData,
            sendCommand = sendCommand
        )
    }

    if (showLoading) {
        LoadingDialog()
    }

    val context = LocalContext.current

    LaunchedEffect(showingError) {
        if (showingError != null) {
            Toast.makeText(context, showingError, Toast.LENGTH_SHORT).show()
            sendCommand(DismissError)
        }
    }
    LaunchedEffect(showingResend) {
        if (showingResend != null) {
            Toast.makeText(context, showingResend, Toast.LENGTH_SHORT).show()
            sendCommand(DismissResend)
        }
    }
}

@Composable
fun ManageMemberItem(
    member: GroupMemberState,
    onClick: (address: Address) -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    RadioMemberItem(
        address = Address.fromSerialized(member.accountId.hexString),
        title = member.name,
        subtitle = member.statusLabel,
        subtitleColor = if (member.highlightStatus) {
            LocalColors.current.danger
        } else {
            LocalColors.current.textSecondary
        },
        showAsAdmin = member.showAsAdmin,
        showProBadge = member.showProBadge,
        avatarUIData = member.avatarUIData,
        onClick = onClick,
        modifier = modifier,
        enabled = true,
        selected = selected
    )
}

@Composable
fun ShowRemoveMembersDialog(
    state: ManageGroupMembersViewModel.RemoveMembersState,
    modifier: Modifier = Modifier,
    sendCommand: (ManageGroupMembersViewModel.Commands) -> Unit
) {
    var deleteMessages by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            // hide dialog
            sendCommand(DismissRemoveDialog)
        },
        title = annotatedStringResource(R.string.remove),
        text = annotatedStringResource(state.removeMemberBody),
        content = {
            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(state.removeMemberText),
                    selected = !deleteMessages
                )
            ) {
                deleteMessages = false
            }

            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(state.removeMessagesText),
                    selected = deleteMessages,
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
                    sendCommand(DismissRemoveDialog)
                    sendCommand(RemoveMembers(deleteMessages))
                }
            ),
            DialogButtonData(
                text = GetString(stringResource(R.string.cancel)),
                onClick = {
                    sendCommand(DismissRemoveDialog)
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
            statusLabel = "Promotion failed"
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
            statusLabel = ""
        )

        val (_, _) = remember { mutableStateOf<String?>(null) }

        ManageMembers(
            onBack = {},
            onAddMemberClick = {},
            members = listOf(oneMember, twoMember, threeMember),
            showAddMembers = true,
            showingError = "Error",
            showLoading = false,
            searchQuery = "Test",
            searchFocused = false,
            data = CollapsibleFooterState(
                visible = true,
                collapsed = false,
                footerActionTitle = title,
                footerActionItems = trayItems
            ),
            selectedMembers = emptySet(),
            showingResend = "Resending Invite",
            sendCommand = {},
            removeMembersData = ManageGroupMembersViewModel.RemoveMembersState(),
            uiState = ManageGroupMembersViewModel.UiState(options = emptyList()),
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
            statusLabel = "Promotion failed"
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
            statusLabel = ""
        )

        ManageMembers(
            onBack = {},
            onAddMemberClick = {},
            members = listOf(oneMember, twoMember, threeMember),
            showAddMembers = true,
            showingError = "Error",
            showLoading = false,
            searchQuery = "",
            searchFocused = true,
            data = CollapsibleFooterState(
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
            ),
            selectedMembers = emptySet(),
            showingResend = "Resending Invite",
            sendCommand = {},
            removeMembersData = ManageGroupMembersViewModel.RemoveMembersState(),
            uiState = ManageGroupMembersViewModel.UiState(options = emptyList()),
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
            onAddMemberClick = {},
            members = listOf(),
            showAddMembers = true,
            showingError = "Error",
            showLoading = false,
            searchQuery = "",
            searchFocused = true,
            data = CollapsibleFooterState(
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
            ),
            selectedMembers = emptySet(),
            showingResend = "Resending Invite",
            sendCommand = {},
            removeMembersData = ManageGroupMembersViewModel.RemoveMembersState(),
            uiState = ManageGroupMembersViewModel.UiState(options = emptyList()),
            hasMembers = true,
        )
    }
}
package org.thoughtcrime.securesms.groups.compose

import android.widget.Toast
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.squareup.phrase.Phrase
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.groups.EditGroupViewModel
import org.thoughtcrime.securesms.groups.EditGroupViewModel.CollapsibleFooterState
import org.thoughtcrime.securesms.groups.GroupMemberState
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
import org.thoughtcrime.securesms.ui.SearchBarWithCancel
import org.thoughtcrime.securesms.ui.components.ActionSheet
import org.thoughtcrime.securesms.ui.components.ActionSheetItemData
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
fun EditGroupScreen(
    viewModel: EditGroupViewModel,
    navigateToInviteContact: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    EditGroup(
        onBack = onBack,
        onAddMemberClick = { navigateToInviteContact(viewModel.excludingAccountIDsFromContactSelection) },
        onPromoteClick = viewModel::onPromoteContact,
        members = viewModel.nonAdminMembers.collectAsState().value,
        selectedMembers = viewModel.selectedMembers.collectAsState().value,
        groupName = viewModel.groupName.collectAsState().value,
        showAddMembers = viewModel.showAddMembers.collectAsState().value,
        onResendPromotionClick = viewModel::onResendPromotionClicked,
        showingError = viewModel.error.collectAsState().value,
        onErrorDismissed = viewModel::onDismissError,
        showingResend = viewModel.resendString.collectAsState().value,
        onResendDismissed = viewModel::onDismissResend,
        onMemberClicked = viewModel::onMemberItemClicked,
        hideActionSheet = viewModel::hideActionBottomSheet,
        clickedMember = viewModel.clickedMember.collectAsState().value,
        showLoading = viewModel.inProgress.collectAsState().value,
        searchQuery = viewModel.searchQuery.collectAsState().value,
        searchFocused = viewModel.searchFocused.collectAsState().value,
        data = viewModel.collapsibleFooterState.collectAsState().value,
        onToggleFooter = viewModel::toggleFooter,
        onCloseFooter = viewModel::clearSelection,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSearchFocusChanged = viewModel::onSearchFocusChanged,
        onSearchQueryClear = { viewModel.onSearchQueryChanged("") },
        sendCommands = viewModel::onCommand,
        removeMembersData = viewModel.removeMembersState.collectAsState().value
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGroup(
    onBack: () -> Unit,
    onAddMemberClick: () -> Unit,
    onResendPromotionClick: (accountId: AccountId) -> Unit,
    onPromoteClick: (accountId: AccountId) -> Unit,
    onMemberClicked: (member: GroupMemberState) -> Unit,
    onSearchFocusChanged : (isFocused : Boolean) -> Unit,
    searchFocused : Boolean,
    searchQuery: String,
    data: CollapsibleFooterState,
    onToggleFooter: () -> Unit,
    onCloseFooter: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchQueryClear: () -> Unit,
    hideActionSheet: () -> Unit,
    clickedMember: GroupMemberState?,
    groupName: String,
    members: List<GroupMemberState>,
    selectedMembers: Set<GroupMemberState> = emptySet(),
    showAddMembers: Boolean,
    showingError: String?,
    showingResend:String?,
    onResendDismissed: () -> Unit,
    showLoading: Boolean,
    onErrorDismissed: () -> Unit,
    sendCommands: (command : EditGroupViewModel.Commands) -> Unit,
    removeMembersData: EditGroupViewModel.RemoveMembersState
) {
//    val (showingConfirmRemovingMember, setShowingConfirmRemovingMember) = remember {
//        mutableStateOf<GroupMemberState?>(null)
//    }

    val optionsList: List<EditGroupViewModel.OptionsItem> = listOf(
        EditGroupViewModel.OptionsItem(
            name = LocalResources.current.getString(R.string.membersInvite),
            icon = R.drawable.ic_user_round_plus,
            onClick = { onAddMemberClick() }
        ),
        EditGroupViewModel.OptionsItem(
            name = LocalResources.current.getString(R.string.accountIdOrOnsInvite),
            icon = R.drawable.ic_user_round_search,
            onClick = { onAddMemberClick() }
        )
    )

    Scaffold(
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.manageMembers),
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
                        items = data.footerActionItems
                    ),
                    onCollapsedClicked = onToggleFooter,
                    onClosedClicked = onCloseFooter
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Column(modifier = Modifier
            .padding(paddingValues)
            .consumeWindowInsets(paddingValues)) {

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
                        optionsList.forEachIndexed { index, option ->
                            ItemButton(
                                modifier = Modifier.qaTag(option.qaTag),
                                text = annotatedStringResource(option.name),
                                iconRes = option.icon,
                                shape = when (index) {
                                    0 -> getCellTopShape()
                                    optionsList.lastIndex -> getCellBottomShape()
                                    else -> RectangleShape
                                },
                                onClick = option.onClick,
                            )

                            if (index != optionsList.lastIndex) Divider()
                        }
                    }
                }
            }

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

            SearchBarWithCancel(
                query = searchQuery,
                onValueChanged = onSearchQueryChanged,
                onClear = onSearchQueryClear,
                placeholder = if(searchFocused) "" else LocalResources.current.getString(R.string.search),
                enabled = true,
                isFocused = searchFocused,
                modifier = Modifier.padding(horizontal =LocalDimensions.current.smallSpacing),
                onFocusChanged = onSearchFocusChanged
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // List of members
            LazyColumn(modifier = Modifier
                .weight(1f)
                .imePadding()) {
                items(members) { member ->
                    // Each member's view
                    EditMemberItem(
                        modifier = Modifier.fillMaxWidth(),
                        member = member,
                        onClick = { onMemberClicked(member) },
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
    
    if(removeMembersData.visible){
        ShowRemoveMembersDialog(
            state = removeMembersData,
            sendCommand = sendCommands
        )
    }

    if (showLoading) {
        LoadingDialog()
    }

    val context = LocalContext.current

    LaunchedEffect(showingError) {
        if (showingError != null) {
            Toast.makeText(context, showingError, Toast.LENGTH_SHORT).show()
            onErrorDismissed()
        }
    }
    LaunchedEffect(showingResend) {
        if (showingResend != null) {
            Toast.makeText(context, showingResend, Toast.LENGTH_SHORT).show()
            onResendDismissed()
        }
    }
}

//todo : Delete after implementing collapsing bottom
@Composable
private fun ConfirmRemovingMemberDialog(
    onConfirmed: (accountId: AccountId, removeMessages: Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    member: GroupMemberState,
    groupName: String,
) {
    val context = LocalContext.current
    val buttons = buildList {
        this += DialogButtonData(
            text = GetString(R.string.remove),
            color = LocalColors.current.danger,
            onClick = { onConfirmed(member.accountId, false) }
        )

        this += DialogButtonData(
            text = GetString(R.string.cancel),
            onClick = onDismissRequest,
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        text = annotatedStringResource(Phrase.from(context, R.string.groupRemoveDescription)
            .put(NAME_KEY, member.name)
            .put(GROUP_NAME_KEY, groupName)
            .format()),
        title = AnnotatedString(stringResource(R.string.remove)),
        buttons = buttons
    )
}

// todo : delete after promote admin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberActionSheet(
    member: GroupMemberState,
    onRemove: () -> Unit,
    onPromote: () -> Unit,
    onResendInvite: () -> Unit,
    onResendPromotion: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current

    val options = remember(member) {
        buildList {
            if (member.canRemove) {
                this += ActionSheetItemData(
                    title = context.resources.getQuantityString(R.plurals.groupRemoveUserOnly, 1),
                    iconRes = R.drawable.ic_trash_2,
                    onClick = onRemove,
                    qaTag = R.string.AccessibilityId_removeContact
                )
            }

            if (BuildConfig.BUILD_TYPE != "release" && member.canPromote) {
                this += ActionSheetItemData(
                    title = context.getString(R.string.adminPromoteToAdmin),
                    iconRes = R.drawable.ic_user_filled_custom,
                    onClick = onPromote
                )
            }

            if (member.canResendInvite) {
                this += ActionSheetItemData(
                    title = "Resend invitation",
                    iconRes = R.drawable.ic_mail,
                    onClick = onResendInvite,
                    qaTag = R.string.AccessibilityId_resendInvite,
                )
            }

            if (BuildConfig.BUILD_TYPE != "release" && member.canResendPromotion) {
                this += ActionSheetItemData(
                    title = "Resend promotion",
                    iconRes = R.drawable.ic_mail,
                    onClick = onResendPromotion,
                    qaTag = R.string.AccessibilityId_resendInvite,
                )
            }
        }
    }

    ActionSheet(
        items = options,
        onDismissRequest = onDismissRequest
    )
}

@Composable
fun EditMemberItem(
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
    state: EditGroupViewModel.RemoveMembersState,
    modifier: Modifier = Modifier,
    sendCommand: (EditGroupViewModel.Commands) -> Unit
) {
    var deleteMessages by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            // hide dialog
            sendCommand(EditGroupViewModel.Commands.DismissRemoveDialog)
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
                    sendCommand(EditGroupViewModel.Commands.DismissRemoveDialog)
                    sendCommand(EditGroupViewModel.Commands.RemoveMembers(deleteMessages))
                }
            ),
            DialogButtonData(
                text = GetString(stringResource(R.string.cancel)),
                onClick = {
                    sendCommand(EditGroupViewModel.Commands.DismissRemoveDialog)
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
            label = GetString("Resend"),
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

        val (editingName, setEditingName) = remember { mutableStateOf<String?>(null) }

        EditGroup(
            onBack = {},
            onAddMemberClick = {},
            onPromoteClick = {},
            members = listOf(oneMember, twoMember, threeMember),
            groupName = "Test ",
            showAddMembers = true,
            onResendPromotionClick = {},
            showingError = "Error",
            onErrorDismissed = {},
            onMemberClicked = {},
            hideActionSheet = {},
            clickedMember = oneMember,
            showLoading = false,
            searchQuery = "Test",
            onSearchQueryChanged = { },
            onSearchFocusChanged = { },
            searchFocused = false,
            onSearchQueryClear = {},
            data = CollapsibleFooterState(
                visible = true,
                collapsed = false,
                footerActionTitle = title,
                footerActionItems = trayItems
            ),
            onToggleFooter = {},
            onCloseFooter = {},
            selectedMembers = emptySet(),
            showingResend = "Resending Invite",
            onResendDismissed = {},
            sendCommands = {},
            removeMembersData = EditGroupViewModel.RemoveMembersState()
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

        EditGroup(
            onBack = {},
            onAddMemberClick = {},
            onPromoteClick = {},
            members = listOf(oneMember, twoMember, threeMember),
            groupName = "Test name that is very very long indeed because many words in it",
            showAddMembers = true,
            onResendPromotionClick = {},
            showingError = "Error",
            onErrorDismissed = {},
            onMemberClicked = {},
            hideActionSheet = {},
            clickedMember = null,
            showLoading = false,
            searchQuery = "",
            onSearchQueryChanged = { },
            onSearchFocusChanged = {},
            searchFocused = true,
            onSearchQueryClear = {},
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
            onToggleFooter = {},
            onCloseFooter = {},
            selectedMembers = emptySet(),
            showingResend = "Resending Invite",
            onResendDismissed = {},
            sendCommands = {},
            removeMembersData = EditGroupViewModel.RemoveMembersState()
        )
    }
}
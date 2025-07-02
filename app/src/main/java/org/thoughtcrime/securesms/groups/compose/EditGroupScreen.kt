package org.thoughtcrime.securesms.groups.compose

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.squareup.phrase.Phrase
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.groups.EditGroupViewModel
import org.thoughtcrime.securesms.groups.GroupMemberState
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.components.ActionSheet
import org.thoughtcrime.securesms.ui.components.ActionSheetItemData
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
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
        onResendInviteClick = viewModel::onResendInviteClicked,
        onPromoteClick = viewModel::onPromoteContact,
        onRemoveClick = viewModel::onRemoveContact,
        members = viewModel.members.collectAsState().value,
        groupName = viewModel.groupName.collectAsState().value,
        showAddMembers = viewModel.showAddMembers.collectAsState().value,
        onResendPromotionClick = viewModel::onResendPromotionClicked,
        showingError = viewModel.error.collectAsState().value,
        onErrorDismissed = viewModel::onDismissError,
        onMemberClicked = viewModel::onMemberClicked,
        hideActionSheet = viewModel::hideActionBottomSheet,
        clickedMember = viewModel.clickedMember.collectAsState().value,
        showLoading = viewModel.inProgress.collectAsState().value,
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGroup(
    onBack: () -> Unit,
    onAddMemberClick: () -> Unit,
    onResendInviteClick: (accountId: AccountId) -> Unit,
    onResendPromotionClick: (accountId: AccountId) -> Unit,
    onPromoteClick: (accountId: AccountId) -> Unit,
    onRemoveClick: (accountId: AccountId, removeMessages: Boolean) -> Unit,
    onMemberClicked: (GroupMemberState) -> Unit,
    hideActionSheet: () -> Unit,
    clickedMember: GroupMemberState?,
    groupName: String,
    members: List<GroupMemberState>,
    showAddMembers: Boolean,
    showingError: String?,
    showLoading: Boolean,
    onErrorDismissed: () -> Unit,
) {
    val (showingConfirmRemovingMember, setShowingConfirmRemovingMember) = remember {
        mutableStateOf<GroupMemberState?>(null)
    }

    val maxNameWidth = 240.dp

    Scaffold(
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.manageMembers),
                onBack = onBack,
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues)) {
            GroupMinimumVersionBanner()

            // Group name title
            Text(
                text = groupName,
                style = LocalType.current.h4,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(CenterHorizontally)
                    .widthIn(max = maxNameWidth)
                    .padding(vertical = LocalDimensions.current.smallSpacing),
            )

            // Header & Add member button
            Row(
                modifier = Modifier.padding(
                    horizontal = LocalDimensions.current.smallSpacing,
                    vertical = LocalDimensions.current.xxsSpacing
                ),
                verticalAlignment = CenterVertically
            ) {
                Text(
                    stringResource(R.string.groupMembers),
                    modifier = Modifier.weight(1f),
                    style = LocalType.current.large,
                    color = LocalColors.current.text
                )

                if (showAddMembers) {
                    AccentOutlineButton(
                        stringResource(R.string.membersInvite),
                        onClick = onAddMemberClick,
                        modifier = Modifier.qaTag(R.string.AccessibilityId_membersInvite)
                    )
                }
            }


            // List of members
            LazyColumn(modifier = Modifier.weight(1f).imePadding()) {
                items(members) { member ->
                    // Each member's view
                    EditMemberItem(
                        modifier = Modifier.fillMaxWidth(),
                        member = member,
                        onClick = { onMemberClicked(member) }
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

    if (clickedMember != null) {
        MemberActionSheet(
            onDismissRequest = hideActionSheet,
            onRemove = {
                setShowingConfirmRemovingMember(clickedMember)
                hideActionSheet()
            },
            onPromote = {
                onPromoteClick(clickedMember.accountId)
                hideActionSheet()
            },
            onResendInvite = {
                onResendInviteClick(clickedMember.accountId)
                hideActionSheet()
            },
            onResendPromotion = {
                onResendPromotionClick(clickedMember.accountId)
                hideActionSheet()
            },
            member = clickedMember,
        )
    }

    if (showingConfirmRemovingMember != null) {
        ConfirmRemovingMemberDialog(
            onDismissRequest = {
                setShowingConfirmRemovingMember(null)
            },
            onConfirmed = onRemoveClick,
            member = showingConfirmRemovingMember,
            groupName = groupName,
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
}

@Composable
private fun GroupNameContainer(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        horizontalArrangement = Arrangement.spacedBy(
            LocalDimensions.current.xxxsSpacing,
            CenterHorizontally
        ),
        verticalAlignment = CenterVertically,
        content = content
    )
}

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

            if (BuildConfig.DEBUG && member.canPromote) {
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

            if (BuildConfig.DEBUG && member.canResendPromotion) {
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
    onClick: (accountId: AccountId) -> Unit,
    modifier: Modifier = Modifier
) {
    MemberItem(
        accountId = member.accountId,
        title = member.name,
        subtitle = member.statusLabel,
        subtitleColor = if (member.highlightStatus) {
            LocalColors.current.danger
        } else {
            LocalColors.current.textSecondary
        },
        showAsAdmin = member.showAsAdmin,
        avatarUIData = member.avatarUIData,
        onClick = if(member.clickable) onClick else null,
        modifier = modifier
    ){
        if (member.canEdit) {
            Icon(
                painter = painterResource(R.drawable.ic_circle_dots_custom),
                tint = LocalColors.current.text,
                contentDescription = stringResource(R.string.AccessibilityId_sessionSettings)
            )
        }
    }
}

@Preview
@Composable
private fun EditGroupPreviewSheet() {
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
            clickable = true,
            statusLabel = "Invited"
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
            clickable = true,
            statusLabel = ""
        )

        val (editingName, setEditingName) = remember { mutableStateOf<String?>(null) }

        EditGroup(
            onBack = {},
            onAddMemberClick = {},
            onResendInviteClick = {},
            onPromoteClick = {},
            onRemoveClick = { _, _ -> },
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
            clickable = true,
            statusLabel = "Invited"
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
            clickable = true,
            statusLabel = ""
        )

        EditGroup(
            onBack = {},
            onAddMemberClick = {},
            onResendInviteClick = {},
            onPromoteClick = {},
            onRemoveClick = { _, _ -> },
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
        )
    }
}
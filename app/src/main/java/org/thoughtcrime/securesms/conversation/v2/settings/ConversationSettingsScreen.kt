package org.thoughtcrime.securesms.conversation.v2.settings

import android.annotation.SuppressLint
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import network.loki.messenger.R
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.*
import org.thoughtcrime.securesms.ui.AccountIdHeader
import org.thoughtcrime.securesms.ui.AvatarQrWidget
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.ExpandableText
import org.thoughtcrime.securesms.ui.ItemButton
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.components.AnnotatedTextWithIcon
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.getCellBottomShape
import org.thoughtcrime.securesms.ui.getCellTopShape
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.safeContentWidth
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.ui.theme.dangerButtonColors
import org.thoughtcrime.securesms.ui.theme.monospace
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.transparentButtonColors
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ConversationSettingsScreen(
    viewModel: ConversationSettingsViewModel,
    onBack: () -> Unit,
) {
    val data by viewModel.uiState.collectAsState()
    val dialogsState by viewModel.dialogState.collectAsState()

    ConversationSettings(
        data = data,
        dialogsState = dialogsState,
        sendCommand = viewModel::onCommand,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ConversationSettings(
    data: ConversationSettingsViewModel.UIState,
    dialogsState: ConversationSettingsViewModel.DialogsState,
    sendCommand: (ConversationSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.sessionSettings),
                onBack = onBack,
                actions = {
                    if(data.editCommand != null) {
                        IconButton(onClick = {
                            sendCommand(data.editCommand)
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_pencil),
                                contentDescription = stringResource(id = R.string.edit)
                            )
                        }
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { paddings ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddings)
                .consumeWindowInsets(paddings)
                .padding(
                    horizontal = LocalDimensions.current.spacing,
                )
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // Profile picture
            AvatarQrWidget(
                modifier = Modifier.qaTag(R.string.qa_conversation_settings_avatar),
                showQR = data.showQR,
                expandedAvatar = data.expandedAvatar,
                showBadge = data.qrAddress != null,
                avatarUIData = data.avatarUIData,
                address = data.qrAddress ?: "",
                toggleQR = { sendCommand(ToggleQR) },
                toggleAvatarExpand = { sendCommand(ToggleAvatarExpand) }
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            // name and edit icon
            AnnotatedTextWithIcon(
                modifier = Modifier.qaTag(data.nameQaTag)
                    .fillMaxWidth()
                    .safeContentWidth()
                    .then(
                        // make the component clickable is there is an edit action
                        if (data.editCommand != null) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { sendCommand(data.editCommand) }
                        )
                        else Modifier
                    ),
                text = data.name,
                iconRes = if(data.showProBadge ) R.drawable.ic_pro_badge else null,
                onIconClick = if(data.proBadgeClickable) {{
                    sendCommand(ShowProBadgeCTA)
                }} else null,
                iconSize = 58.sp to 24.sp,
                style = LocalType.current.h4,
            )

            // description or display name
            if (!data.description.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(LocalDimensions.current.xxsSpacing))
                ExpandableText(
                    modifier = Modifier
                        .fillMaxWidth()
                        .safeContentWidth()
                        .qaTag(data.descriptionQaTag),
                    text = data.description,
                    textStyle = LocalType.current.base,
                    textColor = LocalColors.current.textSecondary,
                    buttonTextStyle = LocalType.current.base.bold(),
                    buttonTextColor = LocalColors.current.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            // account Id header
            if(!data.displayAccountIdHeader.isNullOrEmpty()){
                Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
                AccountIdHeader(
                    text = data.displayAccountIdHeader
                )
            }

            // account ID
            if (!data.displayAccountId.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
                val haptics = LocalHapticFeedback.current
                val longPressLabel = stringResource(R.string.accountIDCopy)
                val onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    sendCommand(CopyAccountId)
                }
                Text(
                    modifier = Modifier
                        .qaTag(R.string.qa_conversation_settings_account_id)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { onLongPress() }
                            )
                        }
                        .semantics {
                            onLongClick(label = longPressLabel) {
                                onLongPress()
                                true
                            }
                        },
                    text = data.displayAccountId,
                    textAlign = TextAlign.Center,
                    style = LocalType.current.xl.monospace(),
                    color = LocalColors.current.text
                )
            }

            // settings options
            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))
            data.categories.forEachIndexed { index, optionsCategory ->
                ConversationSettingsCategory(
                    data = optionsCategory
                )

                // add spacing
                when (index) {
                    data.categories.lastIndex -> Spacer(
                        modifier = Modifier.height(
                            LocalDimensions.current.spacing
                        )
                    )

                    else -> Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))
        }

        // Dialogs
        ConversationSettingsDialogs(
            dialogsState = dialogsState,
            sendCommand = sendCommand
        )

        // Loading
        if (data.showLoading) {
            LoadingDialog()
        }
    }
}
@Composable
fun ConversationSettingsCategory(
    modifier: Modifier = Modifier,
    data: ConversationSettingsViewModel.OptionsCategory,
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        if (!data.name.isNullOrEmpty()) {
            Text(
                modifier = Modifier.padding(
                    start = LocalDimensions.current.smallSpacing,
                    bottom = LocalDimensions.current.smallSpacing
                ),
                text = data.name,
                style = LocalType.current.base,
                color = LocalColors.current.textSecondary
            )
        }

        data.items.forEachIndexed { index, items ->
            ConversationSettingsSubCategory(
                data = items
            )

            // add spacing, except on the last one
            if (index < data.items.lastIndex) {
                Spacer(modifier = Modifier.height(LocalDimensions.current.xxsSpacing))
            }
        }
    }
}

@Composable
fun ConversationSettingsSubCategory(
    modifier: Modifier = Modifier,
    data: ConversationSettingsViewModel.OptionsSubCategory,
) {
    Cell(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            data.items.forEachIndexed { index, option ->
                ItemButton(
                    modifier = Modifier.qaTag(option.qaTag),
                    text = annotatedStringResource(option.name),
                    subtitle = option.subtitle,
                    subtitleQaTag = option.subtitleQaTag,
                    enabled = option.enabled,
                    iconRes = option.icon,
                    shape = when (index) {
                        0 -> getCellTopShape()
                        data.items.lastIndex -> getCellBottomShape()
                        else -> RectangleShape
                    },
                    colors = if(data.danger) dangerButtonColors()
                    else transparentButtonColors(),
                    onClick = option.onClick,
                )

                if(index != data.items.lastIndex) Divider()
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@SuppressLint("UnusedContentLambdaTargetStateParameter")
@Preview
@Composable
private fun ConversationSettings1on1Preview() {
    PreviewTheme {
        ConversationSettings(
            sendCommand = {},
            onBack = {},
            data = ConversationSettingsViewModel.UIState(
                name = "Nickname",
                editCommand = ShowGroupEditDialog,
                description = "(Real name)",
                displayAccountId = "05000000000000000000000000000000000000000000000000000000000000000",
                displayAccountIdHeader = "You Account ID",
                avatarUIData = AvatarUIData(
                    listOf(
                        AvatarUIElement(
                            name = "TO",
                            color = primaryBlue
                        )
                    )
                ),
                categories = listOf(
                    ConversationSettingsViewModel.OptionsCategory(
                        items = listOf(
                            ConversationSettingsViewModel.OptionsSubCategory(
                                items = listOf(
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Search",
                                        icon = R.drawable.ic_search,
                                        onClick = {}
                                    ),
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Notifications",
                                        subtitle = "All Messages",
                                        icon = R.drawable.ic_volume_2,
                                        onClick = {}
                                    )
                                )
                            )
                        )
                    ),
                    ConversationSettingsViewModel.OptionsCategory(
                        name = "Admin",
                        items = listOf(
                            ConversationSettingsViewModel.OptionsSubCategory(
                                items = listOf(
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Invite Contacts",
                                        icon = R.drawable.ic_user_round_plus,
                                        onClick = {}
                                    ),
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Manage members",
                                        icon = R.drawable.ic_users_group_custom,
                                        onClick = {}
                                    )
                                )
                            ),
                            ConversationSettingsViewModel.OptionsSubCategory(
                                danger = true,
                                items = listOf(
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Clear Messages",
                                        icon = R.drawable.ic_message_square,
                                        onClick = {}
                                    ),
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Delete Group",
                                        icon = R.drawable.ic_trash_2,
                                        onClick = {}
                                    )
                                )
                            )
                        )
                    )
                ),
            ),
            dialogsState = ConversationSettingsViewModel.DialogsState()
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@SuppressLint("UnusedContentLambdaTargetStateParameter")
@Preview(locale = "en")
@Preview(locale = "ar")
@Composable
private fun ConversationSettings1on1LongNamePreview() {
    PreviewTheme {
        ConversationSettings(
            sendCommand = {},
            onBack = {},
            data = ConversationSettingsViewModel.UIState(
                name = "Nickname that is very long but the text shouldn't be cut off because there is no limit to the display here so it should show the whole thing",
                editCommand = ShowGroupEditDialog,
                description = "This is a long description with a lot of text that should be more than 2 lines and should be truncated but you never know, it depends on size and such things dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk lkasdjfalsdkfjasdklfj lsadkfjalsdkfjsadklf lksdjfalsdkfjasdlkfjasdlkf asldkfjasdlkfja and this is the end",
                displayAccountId = "05000000000000000000000000000000000000000000000000000000000000000",
                showProBadge = true,
                avatarUIData = AvatarUIData(
                    listOf(
                        AvatarUIElement(
                            name = "TO",
                            color = primaryBlue
                        )
                    )
                ),
                categories = listOf(
                    ConversationSettingsViewModel.OptionsCategory(
                        items = listOf(
                            ConversationSettingsViewModel.OptionsSubCategory(
                                items = listOf(
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Search",
                                        icon = R.drawable.ic_search,
                                        onClick = {}
                                    ),
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Notifications",
                                        subtitle = "All Messages",
                                        icon = R.drawable.ic_volume_2,
                                        onClick = {}
                                    )
                                )
                            )
                        )
                    ),
                    ConversationSettingsViewModel.OptionsCategory(
                        name = "Admin",
                        items = listOf(
                            ConversationSettingsViewModel.OptionsSubCategory(
                                items = listOf(
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Invite Contacts",
                                        icon = R.drawable.ic_user_round_plus,
                                        onClick = {}
                                    ),
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Manage members",
                                        icon = R.drawable.ic_users_group_custom,
                                        onClick = {}
                                    )
                                )
                            ),
                            ConversationSettingsViewModel.OptionsSubCategory(
                                items = listOf(
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Clear Messages",
                                        icon = R.drawable.ic_message_square,
                                        onClick = {}
                                    ),
                                    ConversationSettingsViewModel.OptionsItem(
                                        name = "Delete Group",
                                        icon = R.drawable.ic_trash_2,
                                        onClick = {}
                                    )
                                )
                            )
                        )
                    )
                ),
            ),
            dialogsState = ConversationSettingsViewModel.DialogsState()
        )
    }
}

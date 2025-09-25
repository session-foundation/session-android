package org.thoughtcrime.securesms.preferences

import android.annotation.SuppressLint
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.NonTranslatableStringConstants.NETWORK_NAME
import org.thoughtcrime.securesms.debugmenu.DebugActivity
import org.thoughtcrime.securesms.home.PathActivity
import org.thoughtcrime.securesms.messagerequests.MessageRequestsActivity
import org.thoughtcrime.securesms.preferences.SettingsViewModel.AvatarDialogState.TempAvatar
import org.thoughtcrime.securesms.preferences.SettingsViewModel.AvatarDialogState.UserAvatar
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.ClearData
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.HideAnimatedProCTA
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.HideAvatarPickerOptions
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.HideClearDataDialog
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.HideUrlDialog
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.HideUsernameDialog
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.OnAvatarDialogDismissed
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.OnDonateClicked
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.RemoveAvatar
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.SaveAvatar
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.SetUsername
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.ShowAnimatedProCTA
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.ShowAvatarDialog
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.ShowClearDataDialog
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.ShowUrlDialog
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.ShowUsernameDialog
import org.thoughtcrime.securesms.preferences.SettingsViewModel.Commands.UpdateUsername
import org.thoughtcrime.securesms.preferences.appearance.AppearanceSettingsActivity
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsActivity
import org.thoughtcrime.securesms.recoverypassword.RecoveryPasswordActivity
import org.thoughtcrime.securesms.tokenpage.TokenPageActivity
import org.thoughtcrime.securesms.ui.AccountIdHeader
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.AnimatedProfilePicProCTA
import org.thoughtcrime.securesms.ui.AnimatedSessionProActivatedCTA
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.ItemButton
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
import org.thoughtcrime.securesms.ui.PathDot
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.components.AcccentOutlineCopyButton
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.components.AnnotatedTextWithIcon
import org.thoughtcrime.securesms.ui.components.AppBarCloseIcon
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.components.BaseBottomSheet
import org.thoughtcrime.securesms.ui.components.BasicAppBar
import org.thoughtcrime.securesms.ui.components.DialogTitledRadioButton
import org.thoughtcrime.securesms.ui.components.SessionOutlinedTextField
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.safeContentWidth
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.accentTextButtonColors
import org.thoughtcrime.securesms.ui.theme.dangerButtonColors
import org.thoughtcrime.securesms.ui.theme.monospace
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryGreen
import org.thoughtcrime.securesms.ui.theme.primaryYellow
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement
import org.thoughtcrime.securesms.util.push

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onGalleryPicked: () -> Unit,
    onCameraPicked: () -> Unit,
    startAvatarSelection: () -> Unit,
    onBack: () -> Unit,
) {
    val data by viewModel.uiState.collectAsState()

    Settings(
        uiState = data,
        sendCommand = viewModel::onCommand,
        onGalleryPicked = onGalleryPicked,
        onCameraPicked = onCameraPicked,
        startAvatarSelection = startAvatarSelection,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun Settings(
    uiState: SettingsViewModel.UIState,
    onGalleryPicked: () -> Unit,
    onCameraPicked: () -> Unit,
    startAvatarSelection: () -> Unit,
    sendCommand: (SettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            BasicAppBar(
                title = stringResource(R.string.sessionSettings),
                backgroundColor = Color.Transparent,
                navigationIcon = {
                    AppBarCloseIcon(onClose = onBack)
                },
                actions = {
                    val activity = LocalActivity.current

                    IconButton(onClick = {
                        sendCommand(ShowUsernameDialog)
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_pencil),
                            contentDescription = stringResource(id = R.string.edit)
                        )
                    }

                    IconButton(onClick = {
                        activity?.push<QRCodeActivity>()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_qr_code),
                            contentDescription = stringResource(id = R.string.qrCode)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { paddings ->
        // MAIN SCREEN CONTENT
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

            // avatar
            val avatarData = uiState.avatarData
            if(avatarData != null) {
                Box(
                    modifier = Modifier
                        .size(LocalDimensions.current.iconXXLarge)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null // the ripple doesn't look nice as a square with the plus icon on top too
                        ) {
                            sendCommand(ShowAvatarDialog)
                        }
                        .qaTag(R.string.AccessibilityId_profilePicture),
                    contentAlignment = Alignment.Center
                ) {
                    Avatar(
                        size = LocalDimensions.current.iconXXLarge,
                        data = avatarData
                    )

                    // '+' button that sits atop the custom content
                    Image(
                        modifier = Modifier
                            .size(LocalDimensions.current.spacing)
                            .background(
                                shape = CircleShape,
                                color = LocalColors.current.accent
                            )
                            .padding(LocalDimensions.current.xxxsSpacing)
                            .align(Alignment.BottomEnd)
                        ,
                        painter = painterResource(id = if(avatarData.elements.first().remoteFile == null) R.drawable.ic_plus
                        else R.drawable.ic_pencil),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color.Black)
                    )
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            // name
            AnnotatedTextWithIcon(
                modifier = Modifier.qaTag(R.string.AccessibilityId_displayName)
                    .fillMaxWidth()
                    .safeContentWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        sendCommand(ShowUsernameDialog)
                    },
                text = uiState.username,
                iconRes = if(uiState.showProBadge) R.drawable.ic_pro_badge else null,
                onIconClick = null,
                iconSize = 53.sp to 24.sp,
                style = LocalType.current.h5,
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // Account ID
            AccountIdHeader(
                text = stringResource(R.string.accountIdYours)
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            Text(
                modifier = Modifier.qaTag(R.string.AccessibilityId_shareAccountId),
                text = uiState.accountID,
                textAlign = TextAlign.Center,
                style = LocalType.current.xl.monospace(),
                color = LocalColors.current.text
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // Buttons
            Buttons(
                recoveryHidden = uiState.recoveryHidden,
                hasPaths = uiState.hasPath,
                postPro = uiState.isPostPro,
                sendCommand = sendCommand
            )

            // Footer
            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Image(
                modifier = Modifier.qaTag(R.string.sessionNetworkLearnAboutStaking)
                    .height(24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null // the ripple doesn't look nice as a square with the plus icon on top too
                    ) {
                        sendCommand(ShowUrlDialog("https://token.getsession.org"))
                    },
                painter = painterResource(id = R.drawable.ses_logo),
                colorFilter = ColorFilter.tint(LocalColors.current.textSecondary),
                contentDescription = stringResource(R.string.sessionNetworkLearnAboutStaking),
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            Text(
                text = annotatedStringResource(uiState.version),
                style = LocalType.current.small.copy(color = LocalColors.current.textSecondary),
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
        }

        // DIALOGS AND SHEETS

        // loading
        if(uiState.showLoader) {
            LoadingDialog()
        }

        // dialog for the avatar
        if(uiState.showAvatarDialog) {
            AvatarDialog(
                state = uiState.avatarDialogState,
                isPro = uiState.isPro,
                isPostPro = uiState.isPostPro,
                sendCommand = sendCommand,
                startAvatarSelection = startAvatarSelection
            )
        }

        // Animated avatar CTA
        if(uiState.showAnimatedProCTA){
            AnimatedProCTA(
                isPro = uiState.isPro,
                sendCommand = sendCommand
            )
        }

        // donate confirmation
        if(uiState.showUrlDialog != null){
            OpenURLAlertDialog(
                url = uiState.showUrlDialog,
                onDismissRequest = { sendCommand(HideUrlDialog) }
            )
        }

        // bottom sheets with options for avatar: Gallery or photo
        if(uiState.showAvatarPickerOptions) {
            AvatarBottomSheet(
                showCamera = uiState.showAvatarPickerOptionCamera,
                onDismissRequest =  { sendCommand(HideAvatarPickerOptions) },
                onGalleryPicked = onGalleryPicked,
                onCameraPicked = onCameraPicked
            )
        }

        // username
        if(uiState.usernameDialog != null){

            val focusRequester = remember { FocusRequester() }
            LaunchedEffect (Unit) {
                focusRequester.requestFocus()
            }

            AlertDialog(
                onDismissRequest = {
                    // hide dialog
                    sendCommand(HideUsernameDialog)
                },
                title = stringResource(R.string.displayNameSet),
                text = stringResource(R.string.displayNameVisible),
                showCloseButton = true,
                content = {
                    SessionOutlinedTextField(
                        text = uiState.usernameDialog.inputName ?: "",
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .padding(top = LocalDimensions.current.smallSpacing),
                        placeholder = stringResource(R.string.displayNameEnter),
                        innerPadding = PaddingValues(LocalDimensions.current.smallSpacing),
                        onChange = { updatedText ->
                            sendCommand(UpdateUsername(updatedText))
                        },
                        showClear = true,
                        singleLine = true,
                        onContinue = { sendCommand(SetUsername) },
                        error = uiState.usernameDialog.error,
                    )
                },
                buttons = listOf(
                    DialogButtonData(
                        text = GetString(stringResource(id = R.string.save)),
                        enabled = uiState.usernameDialog.setEnabled,
                        onClick = { sendCommand(SetUsername) },
                        qaTag = stringResource(R.string.qa_settings_dialog_username_save),
                    ),
                    DialogButtonData(
                        text = GetString(stringResource(R.string.cancel)),
                        qaTag = stringResource(R.string.qa_settings_dialog_username_cancel),
                    )
                )
            )
        }

        // clear data
        if(uiState.clearDataDialog != SettingsViewModel.ClearDataState.Hidden) {
            ShowClearDataDialog(
                state = uiState.clearDataDialog,
                sendCommand = sendCommand
            )
        }

    }
}

@Composable
fun Buttons(
    recoveryHidden: Boolean,
    hasPaths: Boolean,
    postPro: Boolean,
    sendCommand: (SettingsViewModel.Commands) -> Unit,
) {
    Column(
        modifier = Modifier
    ) {
        val context = LocalContext.current
        val activity = LocalActivity.current

        Row(
            modifier = Modifier
                .padding(top = LocalDimensions.current.xxsSpacing),
            horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing),
        ) {
            AccentOutlineButton(
                stringResource(R.string.share),
                modifier = Modifier.weight(1f),
                onClick = context::sendInvitationToUseSession
            )

            AcccentOutlineCopyButton(
                modifier = Modifier.weight(1f),
                onClick = context::copyPublicKey,
            )
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

        // Add the debug menu in non release builds
        if (BuildConfig.BUILD_TYPE != "release") {
            Cell{
                ItemButton(
                    annotatedStringResource("Debug Menu"),
                    R.drawable.ic_settings,
                ) { activity?.push<DebugActivity>() }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
        }

        Cell {
            Column {
                if(postPro){
                    ItemButton(
                        text = annotatedStringResource(NonTranslatableStringConstants.APP_PRO),
                        icon = {
                            Image(
                                modifier = Modifier.size(LocalDimensions.current.iconLargeAvatar)
                                    .align(Alignment.Center),
                                painter = painterResource(R.drawable.ic_pro_badge),
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.qaTag(R.string.qa_settings_item_pro),
                        colors = accentTextButtonColors()
                    ) {
                        activity?.push<ProSettingsActivity>()
                    }
                    Divider()
                }

                // Invite a friend
                ItemButton(
                    text = annotatedStringResource(R.string.sessionInviteAFriend),
                    iconRes = R.drawable.ic_user_round_plus,
                    modifier = Modifier.qaTag(R.string.AccessibilityId_sessionInviteAFriend)
                ) { context.sendInvitationToUseSession() }
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

        Cell {
            Column {
                // Donate
                ItemButton(
                    text = annotatedStringResource(R.string.donate),
                    iconRes = R.drawable.ic_heart,
                    iconTint = LocalColors.current.accent,
                    modifier = Modifier.qaTag(R.string.qa_settings_item_donate),
                ) {
                    sendCommand(OnDonateClicked)
                }
                Divider()

                Crossfade(if (hasPaths) primaryGreen else primaryYellow, label = "path") {
                    ItemButton(
                        modifier = Modifier.qaTag(R.string.qa_settings_item_path),
                        text = annotatedStringResource(R.string.onionRoutingPath),
                        icon = {
                            PathDot(
                                modifier = Modifier.align(Alignment.Center),
                                dotSize = LocalDimensions.current.iconSmall,
                                color = it
                            )
                        },
                    ) { activity?.push<PathActivity>() }
                }
                Divider()

                // Add the token page option.
                ItemButton(
                    modifier = Modifier.qaTag(R.string.qa_settings_item_session_network),
                    text = annotatedStringResource(NETWORK_NAME),
                    iconRes = R.drawable.ic_sent_custom
                ) { activity?.push<TokenPageActivity>() }
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

        Cell {
            Column {
                ItemButton(annotatedStringResource(R.string.sessionPrivacy),
                    R.drawable.ic_lock_keyhole, Modifier.qaTag(R.string.AccessibilityId_sessionPrivacy)) { activity?.push<PrivacySettingsActivity>() }
                Divider()

                ItemButton(annotatedStringResource(R.string.sessionNotifications),
                    R.drawable.ic_volume_2, Modifier.qaTag(R.string.AccessibilityId_notifications)) { activity?.push<NotificationSettingsActivity>() }
                Divider()

                ItemButton(annotatedStringResource(R.string.sessionConversations),
                    R.drawable.ic_users_round, Modifier.qaTag(R.string.AccessibilityId_sessionConversations)) { activity?.push<ChatSettingsActivity>() }
                Divider()

                ItemButton(annotatedStringResource(R.string.sessionAppearance),
                    R.drawable.ic_paintbrush_vertical, Modifier.qaTag(R.string.AccessibilityId_sessionAppearance)) { activity?.push<AppearanceSettingsActivity>() }
                Divider()

                ItemButton(annotatedStringResource(R.string.sessionMessageRequests),
                    R.drawable.ic_message_square_warning, Modifier.qaTag(R.string.AccessibilityId_sessionMessageRequests)) { activity?.push<MessageRequestsActivity>() }
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

        Cell {
            Column {
                // Only show the recovery password option if the user has not chosen to permanently hide it
                if (!recoveryHidden) {
                    ItemButton(
                        annotatedStringResource(R.string.sessionRecoveryPassword),
                        R.drawable.ic_recovery_password_custom,
                        Modifier.qaTag(R.string.AccessibilityId_sessionRecoveryPasswordMenuItem)
                    ) { activity?.push<RecoveryPasswordActivity>() }
                    Divider()
                }

                ItemButton(annotatedStringResource(R.string.sessionHelp),
                    R.drawable.ic_question_custom, Modifier.qaTag(R.string.AccessibilityId_help)) { activity?.push<HelpSettingsActivity>() }
                Divider()

                ItemButton(
                    text = annotatedStringResource(R.string.sessionClearData),
                    iconRes = R.drawable.ic_trash_2,
                    modifier = Modifier.qaTag(R.string.AccessibilityId_sessionClearData),
                    colors = dangerButtonColors(),
                ) {
                    sendCommand(ShowClearDataDialog)
                }
            }
        }
    }
}

@Composable
fun ShowClearDataDialog(
    state: SettingsViewModel.ClearDataState,
    modifier: Modifier = Modifier,
    sendCommand: (SettingsViewModel.Commands) -> Unit
) {
    var deleteOnNetwork by remember { mutableStateOf(false)}

    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            // hide dialog
            sendCommand(HideClearDataDialog)
        },
        title = stringResource(R.string.clearDataAll),
        text = when(state){
            is SettingsViewModel.ClearDataState.Error -> stringResource(R.string.clearDataErrorDescriptionGeneric)
            is SettingsViewModel.ClearDataState.ConfirmNetwork -> stringResource(R.string.clearDeviceAndNetworkConfirm)
            else -> stringResource(R.string.clearDataAllDescription)
        },
        content = {
            when(state) {
                is SettingsViewModel.ClearDataState.Clearing -> {
                    SmallCircularProgressIndicator(
                        modifier = Modifier.padding(top = LocalDimensions.current.xsSpacing)
                    )
                }

                is SettingsViewModel.ClearDataState.Default -> {
                    DialogTitledRadioButton(
                        option = RadioOption(
                            value = Unit,
                            title = GetString(stringResource(R.string.clearDeviceOnly)),
                            selected = !deleteOnNetwork
                        )
                    ) {
                        deleteOnNetwork = false
                    }

                    DialogTitledRadioButton(
                        option = RadioOption(
                            value = Unit,
                            title = GetString(stringResource(R.string.clearDeviceAndNetwork)),
                            selected = deleteOnNetwork,
                        )
                    ) {
                        deleteOnNetwork = true
                    }
                }

                else -> {}
            }
        },
        buttons = when(state){
            is SettingsViewModel.ClearDataState.Default,
                 is SettingsViewModel.ClearDataState.ConfirmNetwork -> {
                listOf(
                    DialogButtonData(
                        text = GetString(stringResource(id = R.string.clear)),
                        color = LocalColors.current.danger,
                        dismissOnClick = false,
                        onClick = {
                            // clear data based on chosen option
                            sendCommand(ClearData(deleteOnNetwork))
                        }
                    ),
                    DialogButtonData(
                        GetString(stringResource(R.string.cancel))
                    )
                )
            }

            is SettingsViewModel.ClearDataState.Error -> {
                listOf(
                    DialogButtonData(
                        text = GetString(stringResource(id = R.string.clearDevice)),
                        color = LocalColors.current.danger,
                        dismissOnClick = false,
                        onClick = {
                            // clear data based on chosen option
                            sendCommand(ClearData(deleteOnNetwork))
                        }
                    ),
                    DialogButtonData(
                        GetString(stringResource(R.string.cancel))
                    )
                )
            }

            else -> { emptyList() }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun  AvatarBottomSheet(
    showCamera: Boolean,
    onDismissRequest: () -> Unit,
    onGalleryPicked: () -> Unit,
    onCameraPicked: () -> Unit
){
    BaseBottomSheet(
        sheetState = rememberModalBottomSheetState(),
        onDismissRequest = onDismissRequest
    ){
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalDimensions.current.spacing)
                .padding(bottom = LocalDimensions.current.spacing),
            horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.spacing)
        ) {
            AvatarOption(
                modifier = Modifier.qaTag(R.string.AccessibilityId_imageButton),
                title = stringResource(R.string.image),
                iconRes = R.drawable.ic_image,
                onClick = onGalleryPicked
            )

            if(showCamera) {
                AvatarOption(
                    modifier = Modifier.qaTag(R.string.AccessibilityId_cameraButton),
                    title = stringResource(R.string.contentDescriptionCamera),
                    iconRes = R.drawable.ic_camera,
                    onClick = onCameraPicked
                )
            }
        }
    }
}

@Composable
fun AvatarOption(
    modifier: Modifier = Modifier,
    title: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit
){
    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = false),
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            modifier = Modifier
                .size(LocalDimensions.current.iconXLarge)
                .background(
                    shape = CircleShape,
                    color = LocalColors.current.backgroundBubbleReceived,
                )
                .padding(LocalDimensions.current.smallSpacing),
            painter = painterResource(id = iconRes),
            contentScale = ContentScale.Fit,
            contentDescription = null,
            colorFilter = ColorFilter.tint(LocalColors.current.textSecondary)
        )

        Text(
            modifier = Modifier.padding(top = LocalDimensions.current.xxsSpacing),
            text = title,
            style = LocalType.current.base,
            color = LocalColors.current.text
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun AvatarDialog(
    state: SettingsViewModel.AvatarDialogState,
    isPro: Boolean,
    isPostPro: Boolean,
    sendCommand: (SettingsViewModel.Commands) -> Unit,
    startAvatarSelection: () -> Unit,
){
    AlertDialog(
        onDismissRequest = {
            sendCommand(OnAvatarDialogDismissed)
        },
        title = stringResource(R.string.profileDisplayPictureSet),
        content = {
            // custom content that has the displayed images

            // animated Pro title
            if(isPostPro){
                ProBadgeText(
                    modifier = Modifier
                        .padding(
                            top = LocalDimensions.current.xxxsSpacing,
                            bottom = LocalDimensions.current.xsSpacing,
                        )
                        .clickable {
                            sendCommand(ShowAnimatedProCTA)
                        },
                    text = stringResource(if(isPro) R.string.proAnimatedDisplayPictureModalDescription
                    else R.string.proAnimatedDisplayPicturesNonProModalDescription),
                    textStyle = LocalType.current.base.copy(color = LocalColors.current.textSecondary),
                    badgeAtStart = isPro
                )
            }

            // main container that control the overall size and adds the rounded bg
            Box(
                modifier = Modifier
                    .padding(vertical = LocalDimensions.current.smallSpacing)
                    .size(LocalDimensions.current.iconXXLarge)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null // the ripple doesn't look nice as a square with the plus icon on top too
                    ) {
                        startAvatarSelection()
                    }
                    .qaTag(R.string.AccessibilityId_avatarPicker)
                    .background(
                        shape = CircleShape,
                        color = LocalColors.current.backgroundBubbleReceived,
                    ),
                contentAlignment = Alignment.Center
            ) {
                // the image content will depend on state type
                when(val s = state){
                    // user avatar
                    is UserAvatar -> {
                        Avatar(
                            size = LocalDimensions.current.iconXXLarge,
                            data = s.data
                        )
                    }

                    // temporary image
                    is TempAvatar -> {
                        GlideImage(
                            modifier = Modifier
                                .size(LocalDimensions.current.iconXXLarge)
                                .clip(shape = CircleShape,),
                            contentScale = ContentScale.Crop,
                            model = s.data,
                            contentDescription = stringResource(R.string.profileDisplayPicture)
                        )
                    }

                    // empty state
                    else -> {
                        Image(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(LocalDimensions.current.iconSmall)
                                .align(Alignment.Center),
                            painter = painterResource(id = R.drawable.ic_image),
                            contentScale = ContentScale.Fit,
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(LocalColors.current.textSecondary)
                        )
                    }
                }

                // '+' button that sits atop the custom content
                Image(
                    modifier = Modifier
                        .size(LocalDimensions.current.spacing)
                        .background(
                            shape = CircleShape,
                            color = LocalColors.current.accent
                        )
                        .padding(LocalDimensions.current.xxxsSpacing)
                        .align(Alignment.BottomEnd)
                    ,
                    painter = painterResource(id =
                        if(state is SettingsViewModel.AvatarDialogState.NoAvatar) R.drawable.ic_plus
                        else R.drawable.ic_pencil),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.Black)
                )
            }
        },
        showCloseButton = true, // display the 'x' button
        buttons = listOf(
            DialogButtonData(
                text = GetString(R.string.save),
                enabled = state is TempAvatar,
                dismissOnClick = false,
                onClick = { sendCommand(SaveAvatar) }
            ),
            DialogButtonData(
                text = GetString(if(state is TempAvatar) R.string.clear else R.string.remove),
                color = LocalColors.current.danger,
                enabled = state is UserAvatar || // can remove is the user has an avatar set
                        state is TempAvatar, // can clear a temp avatar
                dismissOnClick = false,
                onClick = { sendCommand(RemoveAvatar) }
            )
        )
    )
}

@Composable
fun AnimatedProCTA(
    isPro: Boolean,
    sendCommand: (SettingsViewModel.Commands) -> Unit,
){
    if(isPro) {
        AnimatedSessionProActivatedCTA (
            heroImageBg = R.drawable.cta_hero_animated_bg,
            heroImageAnimatedFg = R.drawable.cta_hero_animated_fg,
            title = stringResource(R.string.proActivated),
            textContent = {
                ProBadgeText(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    text = stringResource(R.string.proAlreadyPurchased),
                    textStyle = LocalType.current.base.copy(color = LocalColors.current.textSecondary)
                )

                Spacer(Modifier.height(2.dp))

                // main message
                Text(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    text = stringResource(R.string.proAnimatedDisplayPicture),
                    textAlign = TextAlign.Center,
                    style = LocalType.current.base.copy(
                        color = LocalColors.current.textSecondary
                    )
                )
            },
            onCancel = { sendCommand(HideAnimatedProCTA) }
        )
    } else {
        AnimatedProfilePicProCTA(
            onDismissRequest = { sendCommand(HideAnimatedProCTA) },
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@SuppressLint("UnusedContentLambdaTargetStateParameter")
@Preview
@Composable
private fun SettingsScreenPreview() {
    PreviewTheme {
        Settings (
            uiState = SettingsViewModel.UIState(
                showLoader = false,
                avatarDialogState = SettingsViewModel.AvatarDialogState.NoAvatar,
                recoveryHidden = false,
                showUrlDialog = null,
                showAvatarDialog = false,
                showAvatarPickerOptionCamera = false,
                showAvatarPickerOptions = false,
                showAnimatedProCTA = false,
                avatarData = AvatarUIData(
                    listOf(
                        AvatarUIElement(
                            name = "TO",
                            color = primaryBlue
                        )
                    )
                ),
                isPro = false,
                isPostPro = true,
                showProBadge = true,
                username = "Atreyu",
                accountID = "053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144",
                hasPath = true,
                version = "1.26.0",
            ),
            sendCommand = {},
            onGalleryPicked = {},
            onCameraPicked = {},
            startAvatarSelection = {},
            onBack = {},

        )
    }
}

@Preview
@Composable
fun PreviewAvatarDialog(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
){
    PreviewTheme(colors) {
        AvatarDialog(
            state = SettingsViewModel.AvatarDialogState.NoAvatar,
            isPro = false,
            isPostPro = false,
            sendCommand = {},
            startAvatarSelection = {}
        )
    }
}

@Preview
@Composable
fun PreviewAvatarSheet(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
){
    PreviewTheme(colors) {
        AvatarBottomSheet(
            showCamera = true,
            onDismissRequest = {},
            onGalleryPicked = {},
            onCameraPicked = {}
        )
    }
}
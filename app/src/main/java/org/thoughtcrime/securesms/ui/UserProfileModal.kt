package org.thoughtcrime.securesms.ui

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.squareup.phrase.Phrase
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.ui.components.SlimAccentOutlineButton
import org.thoughtcrime.securesms.ui.components.SlimOutlineCopyButton
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.monospace
import org.thoughtcrime.securesms.ui.theme.primaryRed
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement
import org.thoughtcrime.securesms.util.UserProfileModalCommands
import org.thoughtcrime.securesms.util.UserProfileModalData

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
fun UserProfileModal(
    data: UserProfileModalData,
    sendCommand: (UserProfileModalCommands) -> Unit,
    onDismissRequest: () -> Unit,
    onPostAction: (() -> Unit)? = null // a function for optional code once an action has been taken
){
    // the user profile modal
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        showCloseButton = true,
        title = null as AnnotatedString?,
        content = {
            // avatar / QR
            AvatarQrWidget(
                showQR = data.showQR,
                expandedAvatar = data.expandedAvatar,
                showBadge = !data.isBlinded,
                avatarUIData = data.avatarUIData,
                address = data.rawAddress,
                toggleQR = { sendCommand(UserProfileModalCommands.ToggleQR) },
                toggleAvatarExpand = { sendCommand(UserProfileModalCommands.ToggleAvatarExpand) }
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // title
            ProBadgeText(
                text = data.name,
                showBadge = data.showProBadge,
                onBadgeClick = if(!data.currentUserPro){{
                    sendCommand(UserProfileModalCommands.ShowProCTA)
                }} else null
            )

            if(!data.subtitle.isNullOrEmpty()){
                Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
                Text(
                    text = data.subtitle,
                    style = LocalType.current.small.copy(color = LocalColors.current.textSecondary)
                )
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // account ID
            AccountIdHeader(
                text = if(data.isBlinded) stringResource(R.string.blindedId) else stringResource(R.string.accountId),
                textStyle = LocalType.current.small,
                textPaddingValues = PaddingValues(
                    horizontal = LocalDimensions.current.smallSpacing,
                    vertical = LocalDimensions.current.xxxsSpacing
                )
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

            Row {
                if(!data.tooltipText.isNullOrEmpty()){
                    Spacer(modifier = Modifier.width(LocalDimensions.current.spacing))
                }

                Text(
                    modifier = Modifier.weight(1f, fill = false)
                        .qaTag(R.string.qa_conversation_settings_account_id),
                    text = data.displayAddress,
                    textAlign = TextAlign.Center,
                    style = LocalType.current.base.monospace(),
                    color = LocalColors.current.text
                )

                if(!data.tooltipText.isNullOrEmpty()){
                    val tooltipState = rememberTooltipState(isPersistent = true)
                    val scope = rememberCoroutineScope()

                    Spacer(modifier = Modifier.width(LocalDimensions.current.xsSpacing))

                    SpeechBubbleTooltip(
                        text = data.tooltipText,
                        tooltipState = tooltipState
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_circle_help),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(LocalColors.current.text),
                            modifier = Modifier
                                .size(LocalDimensions.current.iconXSmall)
                                .clickable {
                                    scope.launch {
                                        if (tooltipState.isVisible) tooltipState.dismiss() else tooltipState.show()
                                    }
                                }
                                .qaTag("Tooltip")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // show a message if the user can't be messaged
            if(data.isBlinded && !data.enableMessage){
                Text(
                    modifier = Modifier.padding(horizontal = LocalDimensions.current.xsSpacing),
                    text = annotatedStringResource(
                        Phrase.from(LocalContext.current, R.string.messageRequestsTurnedOff)
                        .put(NAME_KEY, data.name)
                        .format()
                    ),
                    textAlign = TextAlign.Center,
                    style = LocalType.current.small.copy(color = LocalColors.current.textSecondary)
                )

                Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
            }

            // buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ){
                var buttonModifier: Modifier = Modifier
                if(data.isBlinded){ // this means there is no copy button so the message button should be full width
                    buttonModifier = buttonModifier.widthIn(LocalDimensions.current.minButtonWidth)
                } else { // the copy button will be there so allow for a max stretch with weight = 1f
                    buttonModifier = buttonModifier.weight(1f)
                }

                SlimAccentOutlineButton(
                    modifier = buttonModifier,
                    text = stringResource(R.string.message),
                    enabled = data.enableMessage && data.messageAddress != null,
                    onClick = {
                        // close dialog
                        onDismissRequest()

                        // optional action
                        onPostAction?.invoke()

                        // open conversation with user
                        context.startActivity(
                            ConversationActivityV2.createIntent(
                                context = context, address = data.messageAddress!!
                            )
                        )
                    }
                )

                if(!data.isBlinded){
                    Spacer(modifier = Modifier.width(LocalDimensions.current.xsSpacing))
                    SlimOutlineCopyButton(
                        Modifier.weight(1f),
                        color = LocalColors.current.accentText,
                        onClick = {
                            sendCommand(UserProfileModalCommands.CopyAccountId)
                        }
                    )
                }
            }
        }
    )

    // the pro CTA that comes with UPM
    if(data.showProCTA){
        GenericProCTA(
            onDismissRequest = {
                sendCommand(UserProfileModalCommands.HideSessionProCTA)
            },
        )
    }
}

@Preview
@Composable
private fun PreviewUPM(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        var data by remember {
            mutableStateOf(
                UserProfileModalData(
                    name = "Atreyu",
                    subtitle = "(Neverending)",
                    showProBadge = true,
                    currentUserPro = false,
                    isBlinded = false,
                    tooltipText = null,
                    rawAddress = "053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144",
                    displayAddress = "123456789112345678911234567891123\n123456789112345678911234567891123",
                    threadAddress = Address.Standard(AccountId("053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144")),
                    enableMessage = true,
                    expandedAvatar = false,
                    showQR = false,
                    showProCTA = false,
                    avatarUIData = AvatarUIData(
                        listOf(
                            AvatarUIElement(
                                name = "TO",
                                color = primaryRed
                            )
                        )
                    ),
                    messageAddress = Address.Standard(AccountId("053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144"))
                )
            )
        }

        UserProfileModal(
            data = data,
            onDismissRequest = {},
            sendCommand = { command ->
                when(command){
                    UserProfileModalCommands.ShowProCTA -> {
                        data = data.copy(showProCTA = true)
                    }
                    UserProfileModalCommands.HideSessionProCTA -> {
                        data = data.copy(showProCTA = false)
                    }
                    UserProfileModalCommands.ToggleQR -> {
                        data = data.copy(showQR = !data.showQR)
                    }
                    UserProfileModalCommands.ToggleAvatarExpand -> {
                        data = data.copy(expandedAvatar = !data.expandedAvatar)
                    }
                    else -> {}

                }
            }
        )
    }
}

@Preview
@Composable
private fun PreviewUPMResolved(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        var data by remember {
            mutableStateOf(
                UserProfileModalData(
                    name = "Atreyu",
                    subtitle = "(Neverending)",
                    showProBadge = true,
                    currentUserPro = false,
                    isBlinded = false,
                    tooltipText = "Some tooltip text that is long and should break into multiple line if necessary",
                    rawAddress = "053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144",
                    displayAddress = "12345678911234567891123\n45678911231234567891123\n45678911234567891123",
                    threadAddress = Address.Standard(AccountId("053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144")),
                    enableMessage = true,
                    expandedAvatar = false,
                    showQR = true,
                    showProCTA = false,
                    avatarUIData = AvatarUIData(
                        listOf(
                            AvatarUIElement(
                                name = "TO",
                                color = primaryRed
                            )
                        )
                    ),
                    messageAddress = Address.Standard(AccountId("053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144"))
                )
            )
        }

        UserProfileModal(
            data = data,
            onDismissRequest = {},
            sendCommand = { command ->
                when(command){
                    UserProfileModalCommands.ShowProCTA -> {
                        data = data.copy(showProCTA = true)
                    }
                    UserProfileModalCommands.HideSessionProCTA -> {
                        data = data.copy(showProCTA = false)
                    }
                    UserProfileModalCommands.ToggleQR -> {
                        data = data.copy(showQR = !data.showQR)
                    }
                    UserProfileModalCommands.ToggleAvatarExpand -> {
                        data = data.copy(expandedAvatar = !data.expandedAvatar)
                    }
                    else -> {}

                }
            }
        )
    }
}


@Preview
@Composable
private fun PreviewUPMQR(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        var data by remember {
            mutableStateOf(
                UserProfileModalData(
                    name = "Atreyu",
                    subtitle = "(Neverending)",
                    showProBadge = false,
                    currentUserPro = false,
                    isBlinded = true,
                    tooltipText = "Some tooltip",
                    rawAddress = "053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144",
                    displayAddress = "1111111111...1111111111",
                    threadAddress = Address.Standard(AccountId("053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144")),
                    enableMessage = false,
                    expandedAvatar = false,
                    showQR = false,
                    showProCTA = false,
                    avatarUIData = AvatarUIData(
                        listOf(
                            AvatarUIElement(
                                name = "TO",
                                color = primaryRed
                            )
                        )
                    ),
                    messageAddress = Address.Standard(AccountId("053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144"))
                )
            )
        }

        UserProfileModal(
            data = data,
            onDismissRequest = {},
            sendCommand = {}
        )
    }
}

@Preview
@Composable
private fun PreviewUPMCTA(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        UserProfileModal(
            data = UserProfileModalData(
                name = "Atreyu",
                subtitle = "(Neverending)",
                showProBadge = false,
                currentUserPro = false,
                isBlinded = true,
                tooltipText = "Some tooltip",
                rawAddress = "158342146b...c6ed734na5",
                displayAddress = "158342146b...c6ed734na5",
                threadAddress = Address.Standard(AccountId("053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144")),
                enableMessage = false,
                expandedAvatar = true,
                showQR = false,
                showProCTA = true,
                avatarUIData = AvatarUIData(
                    listOf(
                        AvatarUIElement(
                            name = "TO",
                            color = primaryRed
                        )
                    )
                ),
                messageAddress = Address.Standard(AccountId("053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144"))
            ),
            onDismissRequest = {},
            sendCommand = {}
        )
    }
}
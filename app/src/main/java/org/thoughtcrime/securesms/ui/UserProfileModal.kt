package org.thoughtcrime.securesms.ui

import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.squareup.phrase.Phrase
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2.Companion.ADDRESS
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.components.QrImage
import org.thoughtcrime.securesms.ui.components.SlimAccentOutlineButton
import org.thoughtcrime.securesms.ui.components.SlimOutlineCopyButton
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
){
    // the user profile modal
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        showCloseButton = true,
        title = null as AnnotatedString?,
        content = {
            // avatar / QR
            UserProfileModalAvatarQR(
                data = data,
                sendCommand = sendCommand
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)

            ) {
                Text(
                    text = data.name,
                    style = LocalType.current.h5
                )

                if(data.isPro) {
                    // if the current user (not the user whose profile this is) is not Pro
                    // then they should see the CTA when tapping the badge
                    var proBadgeModifier: Modifier = Modifier
                    if(!data.currentUserPro){
                        proBadgeModifier = proBadgeModifier.clickable {
                            sendCommand(UserProfileModalCommands.ShowProCTA)
                        }
                    }

                    Image(
                        modifier = proBadgeModifier,
                        painter = painterResource(id = R.drawable.ic_pro_badge),
                        contentScale = ContentScale.FillHeight,
                        contentDescription = NonTranslatableStringConstants.APP_PRO,
                    )
                }
            }

            if(!data.subtitle.isNullOrEmpty()){
                Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
                Text(
                    text = data.subtitle,
                    style = LocalType.current.small.copy(color = LocalColors.current.textSecondary)
                )
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            // account ID
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ){
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(color = LocalColors.current.borders)
                )

                Text(
                    modifier = Modifier
                        .border()
                        .padding(
                            horizontal = LocalDimensions.current.smallSpacing,
                            vertical = LocalDimensions.current.xxxsSpacing
                        )
                    ,
                    text = if(data.isBlinded) stringResource(R.string.blindedId) else stringResource(R.string.accountId),
                    style = LocalType.current.small.copy(color = LocalColors.current.textSecondary)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(color = LocalColors.current.borders)
                )
            }

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
                    enabled = data.enableMessage,
                    onClick = {
                        // close dialog
                        onDismissRequest()

                        // open conversation with user
                        context.startActivity(Intent(context, ConversationActivityV2::class.java)
                            .putExtra(ADDRESS, Address.fromSerialized(data.rawAddress))
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
        AnimatedSessionProCTA(
            heroImageBg = R.drawable.cta_hero_generic_bg,
            heroImageAnimatedFg = R.drawable.cta_hero_generic_fg,
            text = Phrase.from(context,R.string.proUserProfileModalCallToAction)
                .put(APP_NAME_KEY, context.getString(R.string.app_name))
                .put(APP_PRO_KEY, NonTranslatableStringConstants.SESSION_PRO)
                .format()
                .toString(),
            features = listOf(
                CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
                CTAFeature.Icon(stringResource(R.string.proFeatureListLongerMessages)),
                CTAFeature.RainbowIcon(stringResource(R.string.proFeatureListLoadsMore)),
            ),
            onUpgrade = {
                sendCommand(UserProfileModalCommands.HideSessionProCTA)
                //todo PRO go to screen once it exists
            },
            onCancel = {
                sendCommand(UserProfileModalCommands.HideSessionProCTA)
            }
        )
    }
}

@Composable
fun UserProfileModalAvatarQR(
    data: UserProfileModalData,
    sendCommand: (UserProfileModalCommands) -> Unit
){
    val animationSpec = tween<Dp>(
        durationMillis = 400,
        easing = FastOutSlowInEasing
    )

    val animationSpecFast = tween<Float>(
        durationMillis = 200,
        easing = FastOutSlowInEasing
    )

    val targetSize = when {
        data.showQR -> LocalDimensions.current.iconXXLargeAvatar
        data.expandedAvatar -> LocalDimensions.current.iconXXLargeAvatar
        else -> LocalDimensions.current.iconXXLarge
    }

    val animatedSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = animationSpec,
        label = "unified_size"
    )

    val animatedCornerRadius by animateDpAsState(
        targetValue = if (data.showQR) {
            LocalDimensions.current.shapeSmall
        } else {
            animatedSize / 2 // round shape
        },
        animationSpec = animationSpec,
        label = "corner_radius"
    )

    // Scale animations for content
    val avatarScale by animateFloatAsState(
        targetValue = if (data.showQR) 0.8f else 1f,
        animationSpec = animationSpecFast,
        label = "avatar_scale"
    )

    val qrScale by animateFloatAsState(
        targetValue = if (data.showQR) 1f else 0.8f,
        animationSpec = animationSpecFast,
        label = "qr_scale"
    )

    val avatarAlpha by animateFloatAsState(
        targetValue = if (data.showQR) 0f else 1f,
        animationSpec = animationSpecFast,
        label = "avatar_alpha"
    )

    val qrAlpha by animateFloatAsState(
        targetValue = if (data.showQR) 1f else 0f,
        animationSpec = animationSpecFast,
        label = "qr_alpha"
    )

    // Badge animations
    val badgeSize by animateDpAsState(
        targetValue = if (data.expandedAvatar || data.showQR) {
            30.dp
        } else {
            LocalDimensions.current.iconMedium
        },
        animationSpec = animationSpec
    )

    // animating the inner padding of the badge otherwise the icon looks too big within the background
    val animatedBadgeInnerPadding by animateDpAsState(
        targetValue = if (data.expandedAvatar) {
            6.dp
        } else {
            5.dp
        },
        animationSpec = animationSpec,
        label = "badge_inner_pd_animation"
    )

    val badgeOffset by animateOffsetAsState(
        targetValue = if (data.showQR) {
            val cornerOffset = LocalDimensions.current.xsSpacing
            Offset(cornerOffset.value, -cornerOffset.value)
        } else if(data.expandedAvatar) {
            Offset(- LocalDimensions.current.contentSpacing.value, 0f)
        } else {
            Offset.Zero
        },
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "badge_offset"
    )

    Box(
        contentAlignment = Alignment.Center
    ) {
        // Main container
        Box(
            modifier = Modifier
                .size(animatedSize)
                .background(
                    color = if (data.showQR) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(animatedCornerRadius)
                ),
            contentAlignment = Alignment.Center
        ) {
            // Avatar with scale and alpha
            Avatar(
                modifier = Modifier
                    .size(animatedSize)
                    .graphicsLayer(
                        alpha = avatarAlpha,
                        scaleX = avatarScale,
                        scaleY = avatarScale
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        sendCommand(UserProfileModalCommands.ToggleAvatarExpand)
                    },
                size = animatedSize,
                maxSizeLoad = LocalDimensions.current.iconXXLargeAvatar,
                data = data.avatarUIData
            )

            // QR with scale and alpha
            Box(
                modifier = Modifier
                    .size(animatedSize)
                    .graphicsLayer(
                        alpha = qrAlpha,
                        scaleX = qrScale,
                        scaleY = qrScale
                    ),
                contentAlignment = Alignment.Center
            ) {
                QrImage(
                    string = data.rawAddress,
                    modifier = Modifier
                        .size(animatedSize)
                        .qaTag(R.string.AccessibilityId_qrCode),
                    icon = R.drawable.session
                )
            }
        }

        // Badge
        Crossfade(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = badgeOffset.x.dp, y = badgeOffset.y.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    sendCommand(UserProfileModalCommands.ToggleQR)
                },
            targetState = data.showQR,
            animationSpec = tween(durationMillis = 200),
            label = "badge_icon"
        ) { showQR ->
            Image(
                modifier = Modifier
                    .size(badgeSize)
                    .background(
                        shape = CircleShape,
                        color = LocalColors.current.accent
                    )
                    .padding(animatedBadgeInnerPadding),
                painter = painterResource(
                    id = when (showQR) {
                        true -> R.drawable.ic_user_filled_custom
                        false -> R.drawable.ic_qr_code
                    }
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.Black)
            )
        }
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
                    isPro = true,
                    currentUserPro = false,
                    isBlinded = false,
                    tooltipText = null,
                    rawAddress = "053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144",
                    displayAddress = "123456789112345678911234567891123\n123456789112345678911234567891123",
                    threadId = 0L,
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
                    )
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
                    isPro = true,
                    currentUserPro = false,
                    isBlinded = false,
                    tooltipText = "Some tooltip text that is long and should break into multiple line if necessary",
                    rawAddress = "053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144",
                    displayAddress = "12345678911234567891123\n45678911231234567891123\n45678911234567891123",
                    threadId = 0L,
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
                    )
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
                    isPro = false,
                    currentUserPro = false,
                    isBlinded = true,
                    tooltipText = "Some tooltip",
                    rawAddress = "053d30141d0d35d9c4b30a8f8880f8464e221ee71a8aff9f0dcefb1e60145cea5144",
                    displayAddress = "1111111111...1111111111",
                    threadId = 0L,
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
                    )
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
                isPro = false,
                currentUserPro = false,
                isBlinded = true,
                tooltipText = "Some tooltip",
                rawAddress = "158342146b...c6ed734na5",
                displayAddress = "158342146b...c6ed734na5",
                threadId = 0L,
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
                )
            ),
            onDismissRequest = {},
            sendCommand = {}
        )
    }
}
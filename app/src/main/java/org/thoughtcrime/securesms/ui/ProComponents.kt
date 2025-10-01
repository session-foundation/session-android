package org.thoughtcrime.securesms.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideSubcomposition
import com.bumptech.glide.integration.compose.RequestState
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.components.FillButtonRect
import org.thoughtcrime.securesms.ui.components.QrImage
import org.thoughtcrime.securesms.ui.components.TertiaryFillButtonRect
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.util.AvatarUIData


@Composable
fun ProBadge(
    modifier: Modifier = Modifier,
    colors: ProBadgeColors = proBadgeColorStandard()
) {
    Box(
        modifier = modifier.clearAndSetSemantics{
            contentDescription = NonTranslatableStringConstants.APP_PRO
        }
    ) {
        Image(
            modifier = Modifier.align(Alignment.Center),
            painter = painterResource(id = R.drawable.ic_pro_badge_bg),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.backgroundColor)
        )

        Image(
            modifier = Modifier.align(Alignment.Center),
            painter = painterResource(id = R.drawable.ic_pro_badge_fg),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.textColor)
        )
    }
}

data class ProBadgeColors(
    val backgroundColor: Color,
    val textColor: Color
)

@Composable
fun proBadgeColorStandard() = ProBadgeColors(
    backgroundColor = LocalColors.current.accent,
    textColor = Color.Black
)

@Composable
fun proBadgeColorOutgoing() = ProBadgeColors(
    backgroundColor = Color.White,
    textColor = Color.Black
)

@Composable
fun proBadgeColorDisabled() = ProBadgeColors(
    backgroundColor = LocalColors.current.disabled,
    textColor = Color.Black
)

@Composable
fun ProBadgeText(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalType.current.h5,
    showBadge: Boolean = true,
    badgeColors: ProBadgeColors = proBadgeColorStandard(),
    badgeAtStart: Boolean = false,
    onBadgeClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.qaTag(stringResource(R.string.qa_pro_badge_component)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(textStyle.lineHeight.value.dp * 0.2f)
    ) {
        val proBadgeContent = @Composable {
            if (showBadge) {
                var proBadgeModifier: Modifier = Modifier
                if (onBadgeClick != null) {
                    proBadgeModifier = proBadgeModifier.clickable {
                        onBadgeClick()
                    }
                }
                ProBadge(
                    modifier = proBadgeModifier.height(textStyle.lineHeight.value.dp * 0.8f)
                        .qaTag(stringResource(R.string.qa_pro_badge_icon)),
                    colors = badgeColors
                )
            }
        }

        val textContent = @Composable {
            Text(
                modifier = Modifier.weight(1f, fill = false)
                    .qaTag(stringResource(R.string.qa_pro_badge_text)),
                text = text,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (badgeAtStart) {
            proBadgeContent()
            textContent()
        } else {
            textContent()
            proBadgeContent()
        }
    }
}

@Preview
@Composable
private fun PreviewProBadgeText(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LocalDimensions.current.smallSpacing),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxsSpacing)
        ) {
            ProBadgeText(text = "Hello Pro", textStyle = LocalType.current.base)
            ProBadgeText(text = "Hello Pro")
            ProBadgeText(text = "Outgoing Badge Color", badgeColors = proBadgeColorOutgoing())
            ProBadgeText(text = "Hello Pro with a very long name that should overflow")
            ProBadgeText(text = "No Badge", showBadge = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionProCTA(
    content: @Composable () -> Unit,
    text: String,
    features: List<CTAFeature>,
    modifier: Modifier = Modifier,
    onUpgrade: () -> Unit,
    onCancel: () -> Unit,
){
    BasicAlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        content = {
            DialogBg {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // hero image
                    BottomFadingEdgeBox(
                        fadingEdgeHeight = 70.dp,
                        fadingColor = LocalColors.current.backgroundSecondary,
                        content = { _ ->
                            content()
                        },
                    )

                    // content
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(LocalDimensions.current.smallSpacing)
                    ) {
                        // title
                        ProBadgeText(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            text = stringResource(R.string.upgradeTo)
                        )

                        Spacer(Modifier.height(LocalDimensions.current.contentSpacing))

                        // main message
                        Text(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            text = text,
                            textAlign = TextAlign.Center,
                            style = LocalType.current.base.copy(
                                color = LocalColors.current.textSecondary
                            )
                        )

                        Spacer(Modifier.height(LocalDimensions.current.contentSpacing))

                        // features
                        features.forEachIndexed { index, feature ->
                            ProCTAFeature(data = feature)
                            if(index < features.size - 1){
                                Spacer(Modifier.height(LocalDimensions.current.xsSpacing))
                            }
                        }

                        Spacer(Modifier.height(LocalDimensions.current.contentSpacing))

                        // buttons
                        Row(
                            Modifier.height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing),
                        ) {
                            AccentFillButtonRect(
                                modifier = Modifier.weight(1f).shimmerOverlay(),
                                text = stringResource(R.string.theContinue),
                                onClick = onUpgrade
                            )

                            TertiaryFillButtonRect(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.cancel),
                                onClick = onCancel
                            )
                        }
                    }
                }
            }
        }
    )
}

sealed interface CTAFeature {
    val text: String

    data class Icon(
        override val text: String,
        @DrawableRes val icon: Int = R.drawable.ic_circle_check,
    ): CTAFeature

    data class RainbowIcon(
        override val text: String,
        @DrawableRes val icon: Int = R.drawable.ic_pro_sparkle_custom,
    ): CTAFeature
}

@Composable
fun SimpleSessionProCTA(
    @DrawableRes heroImage: Int,
    text: String,
    features: List<CTAFeature>,
    modifier: Modifier = Modifier,
    onUpgrade: () -> Unit,
    onCancel: () -> Unit,
){
    SessionProCTA(
        modifier = modifier,
        text = text,
        features = features,
        onUpgrade = onUpgrade,
        onCancel = onCancel,
        content = { CTAImage(heroImage = heroImage) }
    )
}

@Composable
fun CTAImage(
    @DrawableRes heroImage: Int,
){
    Image(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalColors.current.accent),
        contentScale = ContentScale.FillWidth,
        painter = painterResource(id = heroImage),
        contentDescription = null,
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun AnimatedSessionProCTA(
    @DrawableRes heroImageBg: Int,
    @DrawableRes heroImageAnimatedFg: Int,
    text: String,
    features: List<CTAFeature>,
    modifier: Modifier = Modifier,
    onUpgrade: () -> Unit,
    onCancel: () -> Unit,
){
    SessionProCTA(
        modifier = modifier,
        text = text,
        features = features,
        onUpgrade = onUpgrade,
        onCancel = onCancel,
        content = {
            CTAAnimatedImages(
                heroImageBg = heroImageBg,
                heroImageAnimatedFg = heroImageAnimatedFg
            )
        })
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun CTAAnimatedImages(
    @DrawableRes heroImageBg: Int,
    @DrawableRes heroImageAnimatedFg: Int,
){
    Image(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalColors.current.accent),
        contentScale = ContentScale.FillWidth,
        painter = painterResource(id = heroImageBg),
        contentDescription = null,
    )

    GlideSubcomposition(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        model = heroImageAnimatedFg,
    ){
        when (state) {
            is RequestState.Success -> {
                Image(
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                    painter = painter,
                    contentDescription = null,
                )
            }

            else -> {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionProActivatedCTA(
    imageContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    title: String,
    textContent: @Composable ColumnScope.() -> Unit,
    onCancel: () -> Unit,
){
    BasicAlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        content = {
            DialogBg {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // hero image
                    BottomFadingEdgeBox(
                        fadingEdgeHeight = 70.dp,
                        fadingColor = LocalColors.current.backgroundSecondary,
                        content = { _ ->
                            imageContent()
                        },
                    )

                    // content
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(LocalDimensions.current.smallSpacing)
                    ) {
                        // title
                        ProBadgeText(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            text = title,
                            textStyle = LocalType.current.h5,
                            badgeAtStart = true
                        )

                        Spacer(Modifier.height(LocalDimensions.current.contentSpacing))

                        // already have pro
                        textContent()

                        Spacer(Modifier.height(LocalDimensions.current.contentSpacing))

                        // buttons
                        TertiaryFillButtonRect(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            text = stringResource(R.string.close),
                            onClick = onCancel
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun SimpleSessionProActivatedCTA(
    @DrawableRes heroImage: Int,
    title: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    textContent: @Composable ColumnScope.() -> Unit
){
    SessionProActivatedCTA(
        modifier = modifier,
        title = title,
        textContent = textContent,
        onCancel = onCancel,
        imageContent = { CTAImage(heroImage = heroImage) }
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun AnimatedSessionProActivatedCTA(
    @DrawableRes heroImageBg: Int,
    @DrawableRes heroImageAnimatedFg: Int,
    title: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    textContent: @Composable ColumnScope.() -> Unit
){
    SessionProActivatedCTA(
        modifier = modifier,
        title = title,
        textContent = textContent,
        onCancel = onCancel,
        imageContent = {
            CTAAnimatedImages(
                heroImageBg = heroImageBg,
                heroImageAnimatedFg = heroImageAnimatedFg
            )
        })
}

// Reusable generic Pro CTA
@Composable
fun GenericProCTA(
    onDismissRequest: () -> Unit,
    onPostAction: (() -> Unit)? = null // a function for optional code once an action has been taken
){
    val context = LocalContext.current
    AnimatedSessionProCTA(
        heroImageBg = R.drawable.cta_hero_generic_bg,
        heroImageAnimatedFg = R.drawable.cta_hero_generic_fg,
        text = Phrase.from(context,R.string.proUserProfileModalCallToAction)
            .put(APP_NAME_KEY, context.getString(R.string.app_name))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .format()
            .toString(),
        features = listOf(
            CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
            CTAFeature.Icon(stringResource(R.string.proFeatureListLongerMessages)),
            CTAFeature.RainbowIcon(stringResource(R.string.proFeatureListLoadsMore)),
        ),
        onUpgrade = {
            onDismissRequest()
            onPostAction?.invoke()
            //todo PRO go to screen once it exists
        },
        onCancel = {
            onDismissRequest()
        }
    )
}

// Reusable long message  Pro CTA
@Composable
fun LongMessageProCTA(
    onDismissRequest: () -> Unit,
){
    SimpleSessionProCTA(
        heroImage = R.drawable.cta_hero_char_limit,
        text = Phrase.from(LocalContext.current, R.string.proCallToActionLongerMessages)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .format()
            .toString(),
        features = listOf(
            CTAFeature.Icon(stringResource(R.string.proFeatureListLongerMessages)),
            CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
            CTAFeature.RainbowIcon(stringResource(R.string.proFeatureListLoadsMore)),
        ),
        onUpgrade = {
            onDismissRequest()
            //todo PRO go to screen once it exists
        },
        onCancel = {
            onDismissRequest()
        }
    )
}

// Reusable animated profile pic Pro CTA
@Composable
fun AnimatedProfilePicProCTA(
    onDismissRequest: () -> Unit,
){
    AnimatedSessionProCTA(
        heroImageBg = R.drawable.cta_hero_animated_bg,
        heroImageAnimatedFg = R.drawable.cta_hero_animated_fg,
        text = Phrase.from(LocalContext.current, R.string.proAnimatedDisplayPictureCallToActionDescription)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .format()
            .toString(),
        features = listOf(
            CTAFeature.Icon(stringResource(R.string.proFeatureListAnimatedDisplayPicture)),
            CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
            CTAFeature.RainbowIcon(stringResource(R.string.proFeatureListLoadsMore)),
        ),
        onUpgrade = {
            onDismissRequest()
            //todo PRO go to screen once it exists
        },
        onCancel = {
            onDismissRequest()
        }
    )
}

/**
 * Added here for reusability since multiple screens need this dialog
 */
@Composable
fun PinProCTA(
    overTheLimit: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
){
    val context = LocalContext.current
    
    SimpleSessionProCTA(
        modifier = modifier,
        heroImage = R.drawable.cta_hero_pins,
        text = if(overTheLimit)
            Phrase.from(context, R.string.proCallToActionPinnedConversations)
                .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                .format()
                .toString()
        else
            Phrase.from(context, R.string.proCallToActionPinnedConversationsMoreThan)
                .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                .format()
                .toString(),
        features = listOf(
            CTAFeature.Icon(stringResource(R.string.proFeatureListPinnedConversations)),
            CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
            CTAFeature.RainbowIcon(stringResource(R.string.proFeatureListLoadsMore)),
        ),
        onUpgrade = {
            onDismissRequest()
            //todo PRO go to screen once it exists
        },
        onCancel = {
            onDismissRequest()
        }
    )
}

@Preview
@Composable
private fun PreviewProCTA(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        SimpleSessionProCTA(
            heroImage = R.drawable.cta_hero_char_limit,
            text = "This is a description of this Pro feature",
            features = listOf(
                CTAFeature.Icon("Feature one"),
                CTAFeature.Icon("Feature two", R.drawable.ic_eye),
                CTAFeature.RainbowIcon("Feature three"),
            ),
            onUpgrade = {},
            onCancel = {}
        )
    }
}

@Preview
@Composable
private fun PreviewProActivatedCTA(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        SimpleSessionProActivatedCTA(
            heroImage = R.drawable.cta_hero_char_limit,
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
            onCancel = {}
        )
    }
}

@Composable
fun ProCTAFeature(
    data: CTAFeature,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalType.current.base,
    padding: PaddingValues = PaddingValues(horizontal = LocalDimensions.current.xxxsSpacing)
){
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxsSpacing)
    ) {
        when(data){
            is CTAFeature.Icon -> {
                Image(
                    painter = painterResource(id = data.icon),
                    colorFilter = ColorFilter.tint(LocalColors.current.accentText ),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
            is CTAFeature.RainbowIcon -> {
                AnimatedGradientDrawable(
                    vectorRes = data.icon
                )
            }
        }

        Text(
            text = data.text,
            style = textStyle
        )
    }
}

@Composable
fun AvatarQrWidget(
    showQR: Boolean,
    expandedAvatar: Boolean,
    showBadge: Boolean,
    avatarUIData: AvatarUIData,
    address: String,
    toggleQR: () -> Unit,
    toggleAvatarExpand: () -> Unit,
    modifier: Modifier = Modifier,
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
        showQR -> LocalDimensions.current.iconXXLargeAvatar
        expandedAvatar -> LocalDimensions.current.iconXXLargeAvatar
        else -> LocalDimensions.current.iconXXLarge
    }

    val animatedSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = animationSpec,
        label = "unified_size"
    )

    val animatedCornerRadius by animateDpAsState(
        targetValue = if (showQR) {
            LocalDimensions.current.shapeSmall
        } else {
            animatedSize / 2 // round shape
        },
        animationSpec = animationSpec,
        label = "corner_radius"
    )

    // Scale animations for content
    val avatarScale by animateFloatAsState(
        targetValue = if (showQR) 0.8f else 1f,
        animationSpec = animationSpecFast,
        label = "avatar_scale"
    )

    val qrScale by animateFloatAsState(
        targetValue = if (showQR) 1f else 0.8f,
        animationSpec = animationSpecFast,
        label = "qr_scale"
    )

    val avatarAlpha by animateFloatAsState(
        targetValue = if (showQR) 0f else 1f,
        animationSpec = animationSpecFast,
        label = "avatar_alpha"
    )

    val qrAlpha by animateFloatAsState(
        targetValue = if (showQR) 1f else 0f,
        animationSpec = animationSpecFast,
        label = "qr_alpha"
    )

    // Badge animations
    val badgeSize by animateDpAsState(
        targetValue = if (expandedAvatar && !showQR) {
            30.dp
        } else {
            LocalDimensions.current.iconMedium
        },
        animationSpec = animationSpec
    )

    // animating the inner padding of the badge otherwise the icon looks too big within the background
    val animatedBadgeInnerPadding by animateDpAsState(
        targetValue = if (expandedAvatar) {
            6.dp
        } else {
            5.dp
        },
        animationSpec = animationSpec,
        label = "badge_inner_pd_animation"
    )

    val badgeOffset by animateOffsetAsState(
        targetValue = if (showQR) {
            val cornerOffset = LocalDimensions.current.xsSpacing
            Offset(cornerOffset.value, -cornerOffset.value)
        } else if(expandedAvatar) {
            Offset(- LocalDimensions.current.contentSpacing.value, 0f)
        } else {
            Offset.Zero
        },
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "badge_offset"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Main container
        Box(
            modifier = Modifier
                .size(animatedSize)
                .background(
                    color = if (showQR) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(animatedCornerRadius)
                ),
            contentAlignment = Alignment.Center
        ) {
            // Avatar with scale and alpha
            var avatarModifier: Modifier = Modifier
            if(!showQR){
                avatarModifier = avatarModifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    toggleAvatarExpand()
                }
            }
            Avatar(
                modifier = avatarModifier
                    .size(animatedSize)
                    .graphicsLayer(
                        alpha = avatarAlpha,
                        scaleX = avatarScale,
                        scaleY = avatarScale
                    )
                ,
                size = animatedSize,
                maxSizeLoad = LocalDimensions.current.iconXXLargeAvatar,
                data = avatarUIData
            )

            // QR with scale and alpha
            if(showBadge) {
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
                        string = address,
                        modifier = Modifier
                            .size(animatedSize)
                            .qaTag(R.string.AccessibilityId_qrCode),
                        icon = R.drawable.session
                    )
                }
            }
        }

        // Badge
        if(showBadge) {
            Crossfade(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = badgeOffset.x.dp, y = badgeOffset.y.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        toggleQR()
                    },
                targetState = showQR,
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
}

@Composable
fun SessionProSettingsHeader(
    disabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    extraContent: @Composable (() -> Unit)? = null
){
    val color = if(disabled) LocalColors.current.disabled else LocalColors.current.accent
    val accentColourWithLowAlpha = color.copy(alpha = 0.15f)

    Column(
        modifier = modifier.then(
            // make the component clickable is there is an edit action
            if (onClick != null) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            else Modifier
        ),
    ) {
        // UI with radial gradient
        var headerSize by remember { mutableStateOf(IntSize.Zero) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Note: We add 20% to the ratio for the radial background so that it reaches the
            // edges of the screen, or thereabouts.
            val ratio = headerSize.width * 1.2f / headerSize.height

            androidx.compose.animation.AnimatedVisibility(
                visible = headerSize != IntSize.Zero,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
               // if (!LocalColors.current.isLight) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .scale(ratio, 1.5f)
                            .background(
                                Brush.radialGradient(
                                    // Gradient runs from our washed-out accent colour in the center to the background colour at the edges
                                    colors = listOf(accentColourWithLowAlpha, LocalColors.current.background),
                                )
                            )
                    )
               // }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalDimensions.current.spacing)
                    .onSizeChanged { newSizeDp ->
                        headerSize = newSizeDp
                    }
                    .clearAndSetSemantics{
                        contentDescription = NonTranslatableStringConstants.APP_PRO
                    },
                horizontalAlignment = Alignment.CenterHorizontally

            ) {
                Image(
                    modifier = Modifier.size(LocalDimensions.current.iconXXLarge),
                    painter = painterResource(id = R.drawable.session_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(color)
                )

                Spacer(Modifier.height(LocalDimensions.current.xsSpacing))

                Row(
                    modifier = Modifier.height(LocalDimensions.current.smallSpacing)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_session),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(LocalColors.current.text)
                    )

                    Spacer(Modifier.width(LocalDimensions.current.xxxsSpacing))

                    ProBadge(
                        colors = proBadgeColorStandard().copy(
                            backgroundColor = color
                        )
                    )
                }

                extraContent?.let{
                    Spacer(Modifier.height(LocalDimensions.current.smallSpacing))

                    extraContent()
                }
            }
        }


    }
}

@Preview
@Composable
fun PreviewSessionHeader(){
    PreviewTheme {
        Column {
            Spacer(Modifier.height(LocalDimensions.current.xlargeSpacing))

            SessionProSettingsHeader(
                disabled = false
            )

            Spacer(Modifier.height(LocalDimensions.current.xlargeSpacing))

            SessionProSettingsHeader(
                disabled = true
            )
        }
    }
}
package org.thoughtcrime.securesms.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideSubcomposition
import com.bumptech.glide.integration.compose.RequestState
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel
import org.thoughtcrime.securesms.openUrl
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.TertiaryFillButtonRect
import org.thoughtcrime.securesms.ui.components.TitledRadioButton
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryGreen
import org.thoughtcrime.securesms.ui.theme.primaryOrange
import org.thoughtcrime.securesms.ui.theme.primaryPink
import org.thoughtcrime.securesms.ui.theme.primaryPurple
import org.thoughtcrime.securesms.ui.theme.primaryRed
import org.thoughtcrime.securesms.ui.theme.primaryYellow
import org.thoughtcrime.securesms.ui.theme.transparentButtonColors
import kotlin.math.roundToInt

data class RadioOption<T>(
    val value: T,
    val title: GetString,
    val subtitle: GetString? = null,
    @DrawableRes val iconRes: Int? = null,
    val qaTag: GetString? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

data class OptionsCardData<T>(
    val title: GetString?,
    val options: List<RadioOption<T>>
) {
    constructor(title: GetString, vararg options: RadioOption<T>): this(title, options.asList())
    constructor(@StringRes title: Int, vararg options: RadioOption<T>): this(GetString(title), options.asList())
}

@Composable
fun <T> OptionsCard(card: OptionsCardData<T>, onOptionSelected: (T) -> Unit) {
    Column {
        if (card.title != null && card.title.string().isNotEmpty()) {
            Text(
                modifier = Modifier.padding(start = LocalDimensions.current.smallSpacing),
                text = card.title.string(),
                style = LocalType.current.base,
                color = LocalColors.current.textSecondary
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
        }

        Cell {
            LazyColumn(
                modifier = Modifier.heightIn(max = 5000.dp)
            ) {
                itemsIndexed(card.options) { i, it ->
                    if (i != 0) Divider()
                    TitledRadioButton(option = it) { onOptionSelected(it.value) }
                }
            }
        }
    }
}

@Composable
fun LargeItemButtonWithDrawable(
    @StringRes textId: Int,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButtonWithDrawable(
        textId, icon, modifier,
        subtitle = subtitle,
        subtitleQaTag = subtitleQaTag,
        textStyle = LocalType.current.h8,
        colors = colors,
        shape = shape,
        onClick = onClick
    )
}

@Composable
fun ItemButtonWithDrawable(
    @StringRes textId: Int,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    textStyle: TextStyle = LocalType.current.xl,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    ItemButton(
        annotatedStringText = AnnotatedString(stringResource(textId)),
        modifier = modifier,
        icon = {
            Image(
                painter = rememberDrawablePainter(drawable = AppCompatResources.getDrawable(context, icon)),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center)
            )
        },
        textStyle = textStyle,
        subtitle = subtitle,
        subtitleQaTag = subtitleQaTag,
        colors = colors,
        shape = shape,
        onClick = onClick
    )
}

@Composable
fun LargeItemButton(
    @StringRes textId: Int,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    enabled: Boolean = true,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        textId = textId,
        icon = icon,
        modifier = modifier,
        subtitle = subtitle,
        subtitleQaTag = subtitleQaTag,
        enabled = enabled,
        minHeight = LocalDimensions.current.minLargeItemButtonHeight,
        textStyle = LocalType.current.h8,
        colors = colors,
        shape = shape,
        onClick = onClick
    )
}

@Composable
fun LargeItemButton(
    text: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    enabled: Boolean = true,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        text = text,
        icon = icon,
        modifier = modifier,
        subtitle = subtitle,
        subtitleQaTag = subtitleQaTag,
        enabled = enabled,
        minHeight = LocalDimensions.current.minLargeItemButtonHeight,
        textStyle = LocalType.current.h8,
        colors = colors,
        shape = shape,
        onClick = onClick
    )
}

@Composable
fun LargeItemButton(
    annotatedStringText: AnnotatedString,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        modifier = modifier,
        annotatedStringText = annotatedStringText,
        icon = icon,
        enabled = enabled,
        minHeight = LocalDimensions.current.minLargeItemButtonHeight,
        textStyle = LocalType.current.h8,
        colors = colors,
        shape = shape,
        onClick = onClick
    )
}

@Composable
fun ItemButton(
    text: String,
    @DrawableRes icon: Int,
    modifier: Modifier,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    enabled: Boolean = true,
    minHeight: Dp = LocalDimensions.current.minItemButtonHeight,
    textStyle: TextStyle = LocalType.current.xl,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        annotatedStringText = AnnotatedString(text),
        modifier = modifier,
        icon = {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center)
            )
        },
        minHeight = minHeight,
        textStyle = textStyle,
        shape = shape,
        colors = colors,
        subtitle = subtitle,
        subtitleQaTag = subtitleQaTag,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * Courtesy [ItemButton] implementation that takes a [DrawableRes] for the [icon]
 */
@Composable
fun ItemButton(
    @StringRes textId: Int,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    enabled: Boolean = true,
    minHeight: Dp = LocalDimensions.current.minItemButtonHeight,
    textStyle: TextStyle = LocalType.current.xl,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        annotatedStringText = AnnotatedString(stringResource(textId)),
        modifier = modifier,
        icon = icon,
        minHeight = minHeight,
        textStyle = textStyle,
        shape = shape,
        colors = colors,
        subtitle = subtitle,
        subtitleQaTag = subtitleQaTag,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
fun ItemButton(
    annotatedStringText: AnnotatedString,
    icon: Int,
    modifier: Modifier,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    enabled: Boolean = true,
    minHeight: Dp = LocalDimensions.current.minItemButtonHeight,
    textStyle: TextStyle = LocalType.current.xl,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        annotatedStringText = annotatedStringText,
        modifier = modifier,
        subtitle = subtitle,
        subtitleQaTag = subtitleQaTag,
        enabled = enabled,
        icon = {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center)
            )
        },
        minHeight = minHeight,
        textStyle = textStyle,
        colors = colors,
        shape = shape,
        onClick = onClick
    )
}

/**
 * Base [ItemButton] implementation using an AnnotatedString rather than a plain String.
 *
 * A button to be used in a list of buttons, usually in a [Cell] or [Card]
 */
// THIS IS THE FINAL DEEP LEVEL ANNOTATED STRING BUTTON
@Composable
fun ItemButton(
    annotatedStringText: AnnotatedString,
    icon: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    enabled: Boolean = true,
    minHeight: Dp = LocalDimensions.current.minLargeItemButtonHeight,
    textStyle: TextStyle = LocalType.current.xl,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    TextButton(
        modifier = modifier.fillMaxWidth(),
        colors = colors,
        onClick = onClick,
        contentPadding = PaddingValues(),
        enabled = enabled,
        shape = shape,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = LocalDimensions.current.xxsSpacing)
                .size(minHeight)
                .align(Alignment.CenterVertically),
            content = icon
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterVertically)
        ) {
            Text(
                annotatedStringText,
                Modifier
                    .fillMaxWidth(),
                style = textStyle
            )

            subtitle?.let {
                Text(
                    text = it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .qaTag(subtitleQaTag),
                    style = LocalType.current.small,
                )
            }
        }
    }
}

@Preview
@Composable
fun PreviewItemButton() {
    PreviewTheme {
        ItemButton(
            textId = R.string.groupCreate,
            icon = R.drawable.ic_users_group_custom,
            onClick = {}
        )
    }
}

@Preview
@Composable
fun PreviewLargeItemButton() {
    PreviewTheme {
        LargeItemButton(
            textId = R.string.groupCreate,
            icon = R.drawable.ic_users_group_custom,
            onClick = {}
        )
    }
}

@Composable
fun Cell(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                color = LocalColors.current.backgroundSecondary,
            )
            .wrapContentHeight()
            .fillMaxWidth()
    ) {
        content()
    }
}

@Composable
fun getCellTopShape() = RoundedCornerShape(
    topStart = LocalDimensions.current.shapeSmall,
    topEnd = LocalDimensions.current.shapeSmall,
    bottomEnd = 0.dp,
    bottomStart = 0.dp
)

@Composable
fun getCellBottomShape() = RoundedCornerShape(
    topStart =  0.dp,
    topEnd = 0.dp,
    bottomEnd = LocalDimensions.current.shapeSmall,
    bottomStart = LocalDimensions.current.shapeSmall
)

@Composable
fun BottomFadingEdgeBox(
    modifier: Modifier = Modifier,
    fadingEdgeHeight: Dp = LocalDimensions.current.spacing,
    fadingColor: Color = LocalColors.current.background,
    content: @Composable BoxScope.(bottomContentPadding: Dp) -> Unit,
) {
    Box(modifier) {
        this.content(fadingEdgeHeight)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(fadingEdgeHeight)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.9f to fadingColor,
                        tileMode = TileMode.Repeated
                    )
                )
        )
    }
}

@Preview
@Composable
private fun BottomFadingEdgeBoxPreview() {
    Column(modifier = Modifier.background(LocalColors.current.background)) {
        BottomFadingEdgeBox(
            modifier = Modifier
                .height(600.dp)
                .background(LocalColors.current.backgroundSecondary),
            content = { bottomContentPadding ->
                LazyColumn(contentPadding = PaddingValues(bottom = bottomContentPadding)) {
                    items(200) {
                        Text("Item $it",
                            color = LocalColors.current.text,
                            style = LocalType.current.base)
                    }
                }
            },
        )

        AccentOutlineButton(
            modifier = Modifier
                .align(Alignment.CenterHorizontally),
            text = "Do stuff", onClick = {}
        )
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
                        Row(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)

                        ) {
                            Text(
                                text = stringResource(R.string.upgradeTo),
                                style = LocalType.current.h5
                            )

                            Image(
                                painter = painterResource(id = R.drawable.ic_pro_badge),
                                contentScale = ContentScale.FillHeight,
                                contentDescription = NonTranslatableStringConstants.APP_PRO,
                            )
                        }

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
        content = {
        Image(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalColors.current.accent),
            contentScale = ContentScale.FillWidth,
            painter = painterResource(id = heroImage),
            contentDescription = null,
        )
    })
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
        })
}

/**
 * Added here for reusability since multiple screens need this dialog
 */
@Composable
fun PinProCTA(
    overTheLimit: Boolean,
    onUpgrade: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
){
    SimpleSessionProCTA(
        modifier = modifier,
        heroImage = R.drawable.cta_hero_pins,
        text = if(overTheLimit) stringResource(R.string.proCallToActionPinnedConversations)
                else stringResource(R.string.proCallToActionPinnedConversationsMoreThan),
        features = listOf(
            CTAFeature.Icon(stringResource(R.string.proFeatureListPinnedConversations)),
            CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
            CTAFeature.RainbowIcon(stringResource(R.string.proFeatureListLoadsMore)),
        ),
        onUpgrade = onUpgrade,
        onCancel = onCancel
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

@Composable
fun ProCTAFeature(
    data: CTAFeature,
    modifier: Modifier = Modifier
){
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalDimensions.current.xxxsSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxsSpacing)
    ) {
        when(data){
            is CTAFeature.Icon -> {
                Image(
                    painter = painterResource(id = data.icon),
                    colorFilter = ColorFilter.tint(LocalColors.current.accent),
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
            style = LocalType.current.base
        )
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    HorizontalDivider(
        modifier = modifier
            .padding(horizontal = LocalDimensions.current.smallSpacing)
            .padding(start = startIndent),
        color = LocalColors.current.borders,
    )
}

@Composable
fun ProgressArc(progress: Float, modifier: Modifier = Modifier) {
    val text = (progress * 100).roundToInt()

    Box(modifier = modifier) {
        Arc(percentage = progress, modifier = Modifier.align(Alignment.Center))
        Text(
            "${text}%",
            color = Color.White,
            modifier = Modifier.align(Alignment.Center),
            style = LocalType.current.h2
        )
    }
}

@Composable
fun Arc(
    modifier: Modifier = Modifier,
    percentage: Float = 0.25f,
    fillColor: Color = LocalColors.current.accent,
    backgroundColor: Color = LocalColors.current.borders,
    strokeWidth: Dp = 18.dp,
    sweepAngle: Float = 310f,
    startAngle: Float = (360f - sweepAngle) / 2 + 90f
) {
    Canvas(
        modifier = modifier
            .padding(strokeWidth)
            .size(186.dp)
    ) {
        // Background Line
        drawArc(
            color = backgroundColor,
            startAngle,
            sweepAngle,
            false,
            style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round),
            size = Size(size.width, size.height)
        )

        drawArc(
            color = fillColor,
            startAngle,
            percentage * sweepAngle,
            false,
            style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round),
            size = Size(size.width, size.height)
        )
    }
}

@Composable
fun SessionShieldIcon(
    modifier: Modifier = Modifier
) {
    Icon(
        painter = painterResource(R.drawable.ic_recovery_password_custom),
        contentDescription = null,
        modifier = modifier
            .size(16.dp)
            .wrapContentSize(unbounded = true)
    )
}

@Composable
fun LaunchedEffectAsync(block: suspend CoroutineScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { scope.launch(Dispatchers.IO) { block() } }
}

@Composable
fun LoadingArcOr(loading: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(loading) {
        SmallCircularProgressIndicator(color = LocalContentColor.current)
    }
    AnimatedVisibility(!loading) {
        content()
    }
}

@Composable
fun SimplePopup(
    arrowSize: DpSize = DpSize(
        LocalDimensions.current.smallSpacing,
        LocalDimensions.current.xsSpacing
    ),
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val popupBackgroundColour = LocalColors.current.backgroundBubbleReceived

    Popup(
        popupPositionProvider = AboveCenterPositionProvider(),
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier.clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = CenterHorizontally
            ) {
                // Speech bubble card
                Card(
                    shape = RoundedCornerShape(LocalDimensions.current.spacing),
                    colors = CardDefaults.cardColors(
                        containerColor = popupBackgroundColour
                    ),
                    elevation = CardDefaults.elevatedCardElevation(4.dp)
                ) {
                    content()
                }

                // Triangle below the card to make it look like a speech bubble
                Canvas(
                    modifier = Modifier.size(arrowSize)
                ) {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2, size.height)
                        close()
                    }
                    drawPath(
                        path = path,
                        color = popupBackgroundColour
                    )
                }
            }
        }
    }
}

/**
 * Positions the popup above/centered from its parent
 */
class AboveCenterPositionProvider() : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        return IntOffset(
            anchorBounds.topCenter.x - (popupContentSize.width / 2),
            anchorBounds.topCenter.y - popupContentSize.height
        )
    }
}

@Composable
fun SearchBar(
    query: String,
    onValueChanged: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    backgroundColor: Color = LocalColors.current.background
) {
    BasicTextField(
        singleLine = true,
        value = query,
        onValueChange = onValueChanged,
        enabled = enabled,
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LocalDimensions.current.minSearchInputHeight)
                    .background(backgroundColor, MaterialTheme.shapes.small)
            ) {
                Image(
                    painterResource(id = R.drawable.ic_search),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(
                        LocalColors.current.textSecondary
                    ),
                    modifier = Modifier
                        .padding(
                            horizontal = LocalDimensions.current.smallSpacing,
                            vertical = LocalDimensions.current.xxsSpacing
                        )
                        .size(LocalDimensions.current.iconSmall)
                )

                Box(modifier = Modifier.weight(1f)) {
                    innerTextField()
                    if (query.isEmpty() && placeholder != null) {
                        Text(
                            modifier = Modifier.qaTag(R.string.qa_conversation_search_input),
                            text = placeholder,
                            color = LocalColors.current.textSecondary,
                            style = LocalType.current.xl
                        )
                    }
                }

                Image(
                    painterResource(id = R.drawable.ic_x),
                    contentDescription = stringResource(R.string.clear),
                    colorFilter = ColorFilter.tint(
                        LocalColors.current.textSecondary
                    ),
                    modifier = Modifier
                        .qaTag(R.string.qa_conversation_search_clear)
                        .padding(
                            horizontal = LocalDimensions.current.smallSpacing,
                            vertical = LocalDimensions.current.xxsSpacing
                        )
                        .size(LocalDimensions.current.iconSmall)
                        .clickable {
                            onClear()
                        }
                )
            }
        },
        textStyle = LocalType.current.base.copy(color = LocalColors.current.text),
        modifier = modifier,
        cursorBrush = SolidColor(LocalColors.current.text)
    )
}

@Preview
@Composable
fun PreviewSearchBar() {
    PreviewTheme {
        SearchBar(
            query = "",
            onValueChanged = {},
            onClear = {},
            placeholder = "Search"
        )
    }
}

/**
 * The convenience based expandable text which handles some internal state
 */
@Composable
fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalType.current.base,
    buttonTextStyle: TextStyle = LocalType.current.base,
    textColor: Color = LocalColors.current.text,
    buttonTextColor: Color = LocalColors.current.text,
    textAlign: TextAlign = TextAlign.Start,
    @StringRes qaTag: Int? = null,
    collapsedMaxLines: Int = 2,
    expandedMaxLines: Int = Int.MAX_VALUE,
    expandButtonText: String = stringResource(id = R.string.viewMore),
    collapseButtonText: String = stringResource(id = R.string.viewLess),
){
    var expanded by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }
    var maxHeight by remember { mutableStateOf(Dp.Unspecified) }

    val density = LocalDensity.current

    val enableScrolling = expanded && maxHeight != Dp.Unspecified && expandedMaxLines != Int.MAX_VALUE

    BaseExpandableText(
        text = text,
        modifier = modifier,
        textStyle = textStyle,
        buttonTextStyle = buttonTextStyle,
        textColor = textColor,
        buttonTextColor = buttonTextColor,
        textAlign = textAlign,
        qaTag = qaTag,
        collapsedMaxLines = collapsedMaxLines,
        expandedMaxHeight = maxHeight ?: Dp.Unspecified,
        expandButtonText = expandButtonText,
        collapseButtonText = collapseButtonText,
        showButton = showButton,
        expanded = expanded,
        showScroll = enableScrolling,
        onTextMeasured = { textLayoutResult ->
            showButton = expanded || textLayoutResult.hasVisualOverflow
            val lastVisible = (expandedMaxLines - 1).coerceAtMost(textLayoutResult.lineCount - 1)
            val px = textLayoutResult.getLineBottom(lastVisible)          // bottom of that line in px
            maxHeight = with(density) { px.toDp() }
        },
        onTap = if(showButton){ // only expand if there is enough text
            { expanded = !expanded }
        } else null
    )
}

@Preview
@Composable
private fun PreviewExpandedTextShort() {
    PreviewTheme {
        ExpandableText(
            text = "This"
        )
    }
}

@Preview
@Composable
private fun PreviewExpandedTextLongExpanded() {
    PreviewTheme {
        ExpandableText(
            text = "This is a long description with a lot of text that should be more than 2 lines and should be truncated but you never know, it depends on size and such things dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk lkasdjfalsdkfjasdklfj lsadkfjalsdkfjsadklf lksdjfalsdkfjasdlkfjasdlkf asldkfjasdlkfja and this is the end",
        )
    }
}

@Preview
@Composable
private fun PreviewExpandedTextLongMaxLinesExpanded() {
    PreviewTheme {
        ExpandableText(
            text = "This is a long description with a lot of text that should be more than 2 lines and should be truncated but you never know, it depends on size and such things dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk lkasdjfalsdkfjasdklfj lsadkfjalsdkfjsadklf lksdjfalsdkfjasdlkfjasdlkf asldkfjasdlkfja and this is the end",
            expandedMaxLines = 10
        )
    }
}

/**
 * The base stateless version of the expandable text
 */
@Composable
fun BaseExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalType.current.base,
    buttonTextStyle: TextStyle = LocalType.current.base,
    textColor: Color = LocalColors.current.text,
    buttonTextColor: Color = LocalColors.current.text,
    textAlign: TextAlign = TextAlign.Start,
    @StringRes qaTag: Int? = null,
    collapsedMaxLines: Int = 2,
    expandedMaxHeight: Dp = Dp.Unspecified,
    expandButtonText: String = stringResource(id = R.string.viewMore),
    collapseButtonText: String = stringResource(id = R.string.viewLess),
    showButton: Boolean = false,
    expanded: Boolean = false,
    showScroll: Boolean = false,
    onTextMeasured: (TextLayoutResult) -> Unit = {},
    onTap: (() -> Unit)? = null
){
    var textModifier: Modifier = Modifier
    if(qaTag != null) textModifier = textModifier.qaTag(qaTag)
    if(expanded) textModifier = textModifier.height(expandedMaxHeight)
    if(showScroll){
        val scrollState = rememberScrollState()
        val scrollEdge = LocalDimensions.current.xxxsSpacing
        val scrollWidth = 2.dp
        textModifier = textModifier
            .verticalScrollbar(
                state = scrollState,
                scrollbarWidth = scrollWidth,
                edgePadding = scrollEdge
            )
            .verticalScroll(scrollState)
            .padding(end = scrollWidth + scrollEdge * 2)
    }

    Column(
        modifier = modifier.then(
            if(onTap != null) Modifier.clickable { onTap() } else Modifier
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            modifier = textModifier.animateContentSize(),
            onTextLayout = {
                onTextMeasured(it)
            },
            text = text,
            textAlign = textAlign,
            style = textStyle,
            color = textColor,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
        )

        if(showButton) {
            Spacer(modifier = Modifier.height(LocalDimensions.current.xxsSpacing))
            Text(
                text = if (expanded) collapseButtonText else expandButtonText,
                style = buttonTextStyle,
                color = buttonTextColor
            )
        }
    }
}


@Preview
@Composable
private fun PreviewBaseExpandedTextShort() {
    PreviewTheme {
        BaseExpandableText(
            text = "This is a short description"
        )
    }
}

@Preview
@Composable
private fun PreviewBaseExpandedTextShortWithButton() {
    PreviewTheme {
        BaseExpandableText(
            text = "Aaa",
            showButton = true,
            expanded = true
        )
    }
}

@Preview
@Composable
private fun PreviewBaseExpandedTextLong() {
    PreviewTheme {
        BaseExpandableText(
            text = "This is a long description with a lot of text that should be more than 2 lines and should be truncated but you never know, it depends on size and such things dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk lkasdjfalsdkfjasdklfj lsadkfjalsdkfjsadklf lksdjfalsdkfjasdlkfjasdlkf asldkfjasdlkfja and this is the end",
            showButton = true
        )
    }
}

@Preview
@Composable
private fun PreviewBaseExpandedTextLongExpanded() {
    PreviewTheme {
        BaseExpandableText(
            text = "This is a long description with a lot of text that should be more than 2 lines and should be truncated but you never know, it depends on size and such things dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk lkasdjfalsdkfjasdklfj lsadkfjalsdkfjsadklf lksdjfalsdkfjasdlkfjasdlkf asldkfjasdlkfja and this is the end",
            showButton = true,
            expanded = true
        )
    }
}

@Preview
@Composable
private fun PreviewBaseExpandedTextLongExpandedMaxLines() {
    PreviewTheme {
        BaseExpandableText(
            text = "This is a long description with a lot of text that should be more than 2 lines and should be truncated but you never know, it depends on size and such things dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk dfkjdfklj asjdlkj lkjdf lkjsa dlkfjlk asdflkjlksdfjklasdfjasdlkfjasdflk lkasdjfalsdkfjasdklfj lsadkfjalsdkfjsadklf lksdjfalsdkfjasdlkfjasdlkf asldkfjasdlkfja and this is the end",
            showButton = true,
            expanded = true,
            expandedMaxHeight = 200.dp,
            showScroll = true
        )
    }
}

/**
 * Animated gradient drawable that cycle through the gradient colors in a linear animation
 */
@Composable
fun AnimatedGradientDrawable(
    @DrawableRes vectorRes: Int,
    modifier: Modifier = Modifier,
    gradientColors: List<Color> = listOf(
        primaryGreen, primaryBlue, primaryPurple,
        primaryPink, primaryRed, primaryOrange, primaryYellow
    )
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vector_vertical")
    val animatedOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 200f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Icon(
        painter = painterResource(id = vectorRes),
        contentDescription = null,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val gradientBrush = Brush.linearGradient(
                    colors = gradientColors,
                    start = Offset(0f, animatedOffset),
                    end = Offset(0f, animatedOffset + 100f),
                    tileMode = TileMode.Mirror
                )

                drawContent()
                drawRect(
                    brush = gradientBrush,
                    blendMode = BlendMode.SrcAtop
                )
            }
    )
}

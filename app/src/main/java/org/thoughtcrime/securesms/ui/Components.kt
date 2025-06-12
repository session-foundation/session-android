package org.thoughtcrime.securesms.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.components.PrimaryOutlineButton
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.TitledRadioButton
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
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
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        modifier = modifier,
        annotatedStringText = annotatedStringText,
        icon = icon,
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
            modifier = Modifier.fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth()
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
fun Modifier.contentDescription(text: GetString?): Modifier {
    return text?.let {
        val context = LocalContext.current
        semantics { contentDescription = it(context) }
    } ?: this
}

@Composable
fun Modifier.contentDescription(@StringRes id: Int?): Modifier {
    val context = LocalContext.current
    return id?.let { semantics { contentDescription = context.getString(it) } } ?: this
}

@Composable
fun Modifier.contentDescription(text: String?): Modifier {
    return text?.let { semantics { contentDescription = it } } ?: this
}

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
                        1f to fadingColor,
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

        PrimaryOutlineButton(
            modifier = Modifier
                .align(Alignment.CenterHorizontally),
            text = "Do stuff", onClick = {}
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
    fillColor: Color = LocalColors.current.primary,
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
fun RowScope.SessionShieldIcon() {
    Icon(
        painter = painterResource(R.drawable.ic_recovery_password_custom),
        contentDescription = null,
        modifier = Modifier
            .align(Alignment.CenterVertically)
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

// Permanently visible vertical scrollbar.
// Note: This scrollbar modifier was adapted from Mardann's fantastic solution at: https://stackoverflow.com/a/78453760/24337669
@Composable
fun Modifier.verticalScrollbar(
    state: ScrollState,
    scrollbarWidth: Dp = 6.dp,
    barColour: Color = LocalColors.current.textSecondary,
    backgroundColour: Color = LocalColors.current.borders,
    edgePadding: Dp = LocalDimensions.current.xxsSpacing
): Modifier {
    // Calculate the viewport and content heights
    val viewHeight    = state.viewportSize.toFloat()
    val contentHeight = state.maxValue + viewHeight

    // Determine if the scrollbar is needed
    val isScrollbarNeeded = contentHeight > viewHeight

    // Set the target alpha based on whether scrolling is possible
    val alphaTarget = when {
        !isScrollbarNeeded       -> 0f // No scrollbar needed, set alpha to 0f
        state.isScrollInProgress -> 1f
        else                     -> 0.2f
    }

    // Animate the alpha value smoothly
    val alpha by animateFloatAsState(
        targetValue   = alphaTarget,
        animationSpec = tween(400, delayMillis = if (state.isScrollInProgress) 0 else 700),
        label         = "VerticalScrollbarAnimation"
    )

    return this.then(Modifier.drawWithContent {
        drawContent()

        // Only proceed if the scrollbar is needed
        if (isScrollbarNeeded) {
            val minScrollBarHeight = 10.dp.toPx()
            val maxScrollBarHeight = viewHeight
            val scrollbarHeight = (viewHeight * (viewHeight / contentHeight)).coerceIn(
                minOf(minScrollBarHeight, maxScrollBarHeight)..maxOf(minScrollBarHeight, maxScrollBarHeight)
            )
            val variableZone = viewHeight - scrollbarHeight
            val scrollbarYoffset = (state.value.toFloat() / state.maxValue) * variableZone

            // Calculate the horizontal offset with padding
            val scrollbarXOffset = size.width - scrollbarWidth.toPx() - edgePadding.toPx()

            // Draw the missing section of the scrollbar track
            drawRoundRect(
                color = backgroundColour,
                topLeft = Offset(scrollbarXOffset, 0f),
                size = Size(scrollbarWidth.toPx(), viewHeight),
                cornerRadius = CornerRadius(scrollbarWidth.toPx() / 2),
                alpha = alpha
            )

            // Draw the scrollbar thumb
            drawRoundRect(
                color = barColour,
                topLeft = Offset(scrollbarXOffset, scrollbarYoffset),
                size = Size(scrollbarWidth.toPx(), scrollbarHeight),
                cornerRadius = CornerRadius(scrollbarWidth.toPx() / 2),
                alpha = alpha
            )
        }
    })
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
                    modifier = Modifier.qaTag(R.string.qa_conversation_search_clear)
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

    val enableScrolling = expanded && maxHeight != Dp.Unspecified

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
            text = "This is a short description"
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
            .padding(end = scrollWidth + scrollEdge*2)
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
 * Applies an opinionated safety width on content based our design decisions:
 * - Max width of maxContentWidth
 * - Extra horizontal padding
 * - Smaller extra padding for small devices (arbitrarily decided as devices below 380 width
 */
@Composable
fun Modifier.safeContentWidth(
    regularExtraPadding: Dp = LocalDimensions.current.mediumSpacing,
    smallExtraPadding: Dp = LocalDimensions.current.xsSpacing,
): Modifier {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp

    return this.widthIn(max = LocalDimensions.current.maxContentWidth)
        .padding(
            horizontal = when {
                screenWidthDp < 380.dp -> smallExtraPadding
                else -> regularExtraPadding
            }
        )
}

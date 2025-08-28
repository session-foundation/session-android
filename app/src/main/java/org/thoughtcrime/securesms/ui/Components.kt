package org.thoughtcrime.securesms.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.components.SessionSwitch
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.TitledRadioButton
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryGreen
import org.thoughtcrime.securesms.ui.theme.primaryOrange
import org.thoughtcrime.securesms.ui.theme.primaryPink
import org.thoughtcrime.securesms.ui.theme.primaryPurple
import org.thoughtcrime.securesms.ui.theme.primaryRed
import org.thoughtcrime.securesms.ui.theme.primaryYellow
import org.thoughtcrime.securesms.ui.theme.transparentButtonColors
import kotlin.math.roundToInt

@Composable
fun AccountIdHeader(
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.accountId),
    textStyle: TextStyle = LocalType.current.base,
    textPaddingValues: PaddingValues = PaddingValues(
        horizontal = LocalDimensions.current.contentSpacing,
        vertical = LocalDimensions.current.xxsSpacing
    )
){
    Row(
        modifier = modifier,
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
                .border(
                    shape = MaterialTheme.shapes.large
                )
                .padding(textPaddingValues)
            ,
            text = text,
            style = textStyle.copy(color = LocalColors.current.textSecondary)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(color = LocalColors.current.borders)
        )
    }
}

@Composable
fun PathDot(
    modifier: Modifier = Modifier,
    dotSize: Dp = LocalDimensions.current.iconMedium,
    glowSize: Dp = LocalDimensions.current.xxsSpacing,
    color: Color = primaryGreen
) {
    val fullSize = dotSize + 2 * glowSize
    Box(
        modifier = modifier.size(fullSize),
        contentAlignment = Alignment.Center
    ) {
        // Glow effect (outer circle with radial gradient)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val radius = (fullSize * 0.5f).toPx()

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color, // Start color (opaque)
                        color.copy(alpha = 0f)  // End color (transparent)
                    ),
                    center = center,
                    radius = radius
                ),
                center = center,
                radius = radius
            )
        }

        // Inner solid dot
        Box(
            modifier = Modifier
                .size(dotSize)
                .background(
                    color = color,
                    shape = CircleShape
                )
        )
    }
}

@Preview
@Composable
fun PreviewPathDot(){
    PreviewTheme {
        Box(
            modifier = Modifier.padding(20.dp)
        ) {
            PathDot()
        }
    }
}


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
fun ItemButton(
    text: AnnotatedString,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalType.current.h8,
    iconTint: Color? = null,
    iconSize: Dp = LocalDimensions.current.iconRowItem,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    enabled: Boolean = true,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        text = text,
        modifier = modifier,
        subtitle = subtitle,
        subtitleQaTag = subtitleQaTag,
        enabled = enabled,
        icon = {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = iconTint ?: colors.contentColor,
                modifier = Modifier.align(Alignment.Center)
                    .size(iconSize)
            )
        },
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
@Composable
fun ItemButton(
    text: AnnotatedString,
    icon: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @StringRes subtitleQaTag: Int? = null,
    enabled: Boolean = true,
    minHeight: Dp = LocalDimensions.current.minItemButtonHeight,
    textStyle: TextStyle = LocalType.current.h8,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    TextButton(
        modifier = modifier.fillMaxWidth()
            .heightIn(min = minHeight),
        colors = colors,
        onClick = onClick,
        contentPadding = PaddingValues(
            start = LocalDimensions.current.smallSpacing,
            end = LocalDimensions.current.smallSpacing
        ),
        enabled = enabled,
        shape = shape,
    ) {
        Box(
            modifier = Modifier.size(LocalDimensions.current.itemButtonIconSpacing)
        ) {
            icon()
        }

        Spacer(Modifier.width(LocalDimensions.current.smallSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterVertically)
        ) {
            Text(
                text,
                Modifier.fillMaxWidth(),
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
            text = annotatedStringResource(R.string.groupCreate),
            iconRes = R.drawable.ic_users_group_custom,
            onClick = {}
        )
    }
}

@Composable
fun Cell(
    modifier: Modifier = Modifier,
    dropShadow: Boolean = false,
    bgColor: Color = LocalColors.current.backgroundSecondary,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .then(
                if(dropShadow)
                    Modifier.dropShadow(
                        shape = MaterialTheme.shapes.small,
                        shadow = Shadow(
                            radius = 4.dp,
                            color = LocalColors.current.text,
                            alpha = 0.25f,
                            offset = DpOffset(0.dp, 4.dp)
                        )
                    )
                else Modifier
            )
            .clip(MaterialTheme.shapes.small)
            .background(
                color = bgColor,
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
fun CategoryCell(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleIcon: @Composable (() -> Unit)? = null,
    dropShadow: Boolean = false,
    content: @Composable () -> Unit,

){
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        if(!title.isNullOrEmpty() || titleIcon != null) {
            Row(
                modifier = Modifier.padding(
                    start = LocalDimensions.current.smallSpacing,
                    bottom = LocalDimensions.current.smallSpacing
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)
            ) {
                if (!title.isNullOrEmpty()) {
                    Text(
                        text = title,
                        style = LocalType.current.base,
                        color = LocalColors.current.textSecondary
                    )
                }

                titleIcon?.invoke()
            }
        }

       Cell(
           modifier = Modifier.fillMaxWidth(),
           dropShadow = dropShadow
       ){
            content()
       }
    }
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

@Composable
fun Divider(
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues = PaddingValues(horizontal = LocalDimensions.current.smallSpacing)
) {
    HorizontalDivider(
        modifier = modifier.padding(paddingValues),
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechBubbleTooltip(
    text: CharSequence,
    modifier: Modifier = Modifier,
    maxWidth: Dp = LocalDimensions.current.maxTooltipWidth,
    tooltipState: TooltipState = rememberTooltipState(),
    content: @Composable () -> Unit,
) {
    TooltipBox(
        state = tooltipState,
        modifier = modifier,
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = {
            val bubbleColor = LocalColors.current.backgroundBubbleReceived

            Card(
                modifier = Modifier.widthIn(max = maxWidth),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = bubbleColor),
                elevation = CardDefaults.elevatedCardElevation(4.dp)
            ) {
                Text(
                    text = annotatedStringResource(text),
                    modifier = Modifier.padding(
                        horizontal = LocalDimensions.current.xsSpacing,
                        vertical = LocalDimensions.current.xxsSpacing
                    ),
                    textAlign = TextAlign.Center,
                    style = LocalType.current.small,
                    color = LocalColors.current.text
                )
            }
        }
    ) {
        content()
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
                        .qaTag(R.string.qa_input_clear)
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


@Composable
fun ActionRowItem(
    title: AnnotatedString,
    onClick: () -> Unit,
    @StringRes qaTag: Int,
    modifier: Modifier = Modifier,
    subtitle: AnnotatedString? = null,
    titleColor: Color = LocalColors.current.text,
    subtitleColor: Color = LocalColors.current.text,
    textStyle: TextStyle = LocalType.current.h8,
    subtitleStyle: TextStyle = LocalType.current.small,
    minHeight: Dp = LocalDimensions.current.minItemButtonHeight,
    paddingValues: PaddingValues = PaddingValues(horizontal = LocalDimensions.current.smallSpacing),
    endContent: @Composable (() -> Unit)? = null
){
    Row(
        modifier = modifier.heightIn(min = minHeight)
            .clickable { onClick() }
            .padding(paddingValues)
            .qaTag(qaTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = LocalDimensions.current.xsSpacing)
                .align(Alignment.CenterVertically)
        ) {
            Text(
                title,
                Modifier
                    .fillMaxWidth()
                    .qaTag(R.string.qa_action_item_title),
                style = textStyle,
                color = titleColor
            )

            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .qaTag(R.string.qa_action_item_subtitle),
                    style = subtitleStyle,
                    color = subtitleColor
                )
            }
        }

        endContent?.invoke()
    }
}

@Composable
fun IconActionRowItem(
    title: AnnotatedString,
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    @StringRes qaTag: Int,
    modifier: Modifier = Modifier,
    subtitle: AnnotatedString? = null,
    titleColor: Color = LocalColors.current.text,
    subtitleColor: Color = LocalColors.current.text,
    textStyle: TextStyle = LocalType.current.h8,
    subtitleStyle: TextStyle = LocalType.current.small,
    iconColor: Color = LocalColors.current.text,
    iconSize: Dp = LocalDimensions.current.iconRowItem,
    minHeight: Dp = LocalDimensions.current.minItemButtonHeight,
    paddingValues: PaddingValues = PaddingValues(horizontal = LocalDimensions.current.smallSpacing),
){
    ActionRowItem(
        modifier = modifier,
        title = title,
        onClick = onClick,
        qaTag = qaTag,
        subtitle = subtitle,
        titleColor = titleColor,
        subtitleColor = subtitleColor,
        textStyle = textStyle,
        subtitleStyle = subtitleStyle,
        minHeight = minHeight,
        paddingValues = paddingValues,
        endContent = {
            Box(
                modifier = Modifier.size(LocalDimensions.current.itemButtonIconSpacing)
            ) {
                Icon(
                    modifier = Modifier.align(Alignment.Center)
                        .size(iconSize)
                        .qaTag(R.string.qa_action_item_icon),
                    painter = painterResource(id = icon),
                    contentDescription = null,
                    tint = iconColor
                )
            }
        }
    )
}

@Composable
fun SwitchActionRowItem(
    title: AnnotatedString,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @StringRes qaTag: Int,
    modifier: Modifier = Modifier,
    subtitle: AnnotatedString? = null,
    titleColor: Color = LocalColors.current.text,
    subtitleColor: Color = LocalColors.current.text,
    textStyle: TextStyle = LocalType.current.h8,
    subtitleStyle: TextStyle = LocalType.current.small,
    paddingValues: PaddingValues = PaddingValues(horizontal = LocalDimensions.current.smallSpacing),
    minHeight: Dp = LocalDimensions.current.minItemButtonHeight,
){
    ActionRowItem(
        modifier = modifier,
        title = title,
        qaTag = qaTag,
        onClick = { onCheckedChange(!checked) },
        subtitle = subtitle,
        titleColor = titleColor,
        subtitleColor = subtitleColor,
        textStyle = textStyle,
        subtitleStyle = subtitleStyle,
        paddingValues = paddingValues,
        minHeight = minHeight,
        endContent = {
            SessionSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Preview
@Composable
fun PreviewActionRowItems(){
    PreviewTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconActionRowItem(
                title = annotatedStringResource("This is an action row item"),
                subtitle = annotatedStringResource("With a subtitle and icon"),
                onClick = {},
                icon = R.drawable.ic_message_square,
                qaTag = 0
            )

            IconActionRowItem(
                title = annotatedStringResource("This is an action row item"),
                subtitle = annotatedStringResource("With a subtitle and icon"),
                titleColor = LocalColors.current.danger,
                subtitleColor = LocalColors.current.danger,
                onClick = {},
                icon = R.drawable.ic_triangle_alert,
                iconColor = LocalColors.current.danger,
                qaTag = 0
            )

            SwitchActionRowItem(
                title = annotatedStringResource("This is an action row item"),
                subtitle = annotatedStringResource("With a subtitle and a switch"),
                checked = true,
                onCheckedChange = {},
                qaTag = 0
            )
        }
    }
}
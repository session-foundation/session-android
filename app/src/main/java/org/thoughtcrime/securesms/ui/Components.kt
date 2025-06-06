package org.thoughtcrime.securesms.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.components.ProfilePictureView
import org.thoughtcrime.securesms.conversation.disappearingmessages.ui.OptionsCardData
import org.thoughtcrime.securesms.ui.components.PrimaryOutlineButton
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.TitledRadioButton
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.transparentButtonColors
import kotlin.math.roundToInt

interface Callbacks<in T> {
    fun onSetClick(): Any?
    fun setValue(value: T)
}

object NoOpCallbacks: Callbacks<Any> {
    override fun onSetClick() {}
    override fun setValue(value: Any) {}
}

data class RadioOption<T>(
    val value: T,
    val title: GetString,
    val subtitle: GetString? = null,
    val contentDescription: GetString = title,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
fun <T> OptionsCard(card: OptionsCardData<T>, callbacks: Callbacks<T>) {
    Column {
        Text(
            modifier = Modifier.padding(start = LocalDimensions.current.smallSpacing),
            text = card.title(),
            style = LocalType.current.base,
            color = LocalColors.current.textSecondary
        )

        Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

        Cell {
            LazyColumn(
                modifier = Modifier.heightIn(max = 5000.dp)
            ) {
                itemsIndexed(card.options) { i, it ->
                    if (i != 0) Divider()
                    TitledRadioButton(option = it) { callbacks.setValue(it.value) }
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
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButtonWithDrawable(
        textId, icon, modifier,
        LocalType.current.h8, colors, shape, onClick
    )
}

@Composable
fun ItemButtonWithDrawable(
    @StringRes textId: Int,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
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
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        textId = textId,
        icon = icon,
        modifier = modifier,
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
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        text = text,
        icon = icon,
        modifier = modifier,
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
    minHeight: Dp = LocalDimensions.current.minItemButtonHeight,
    textStyle: TextStyle = LocalType.current.xl,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        annotatedStringText = AnnotatedString(text),
        modifier = modifier,
        icon = icon,
        minHeight = minHeight,
        textStyle = textStyle,
        colors = colors,
        shape = shape,
        onClick = onClick
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
        onClick = onClick
    )
}

@Composable
fun ItemButton(
    annotatedStringText: AnnotatedString,
    icon: Int,
    modifier: Modifier,
    minHeight: Dp = LocalDimensions.current.minItemButtonHeight,
    textStyle: TextStyle = LocalType.current.xl,
    colors: ButtonColors = transparentButtonColors(),
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    ItemButton(
        annotatedStringText = annotatedStringText,
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

        Text(
            annotatedStringText,
            Modifier
                .fillMaxWidth()
                .align(Alignment.CenterVertically),
            style = textStyle
        )
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

//TODO This component should be fully rebuilt in Compose at some point ~~
@Composable
private fun BaseAvatar(
    modifier: Modifier = Modifier,
    isAdmin: Boolean = false,
    update: (ProfilePictureView)->Unit
){
    Box(
        modifier = modifier
    ) {
        // image
        if (LocalInspectionMode.current) { // this part is used for previews only
            Image(
                painterResource(id = R.drawable.ic_user_filled_custom),
                colorFilter = ColorFilter.tint(LocalColors.current.textSecondary),
                contentScale = ContentScale.Inside,
                contentDescription = null,
                modifier = Modifier
                    .size(LocalDimensions.current.iconLarge)
                    .clip(CircleShape)
                    .border(1.dp, LocalColors.current.borders, CircleShape)
            )
        } else {
            AndroidView(
                factory = {
                    ProfilePictureView(it)
                },
                update = update
            )
        }

        // badge
        if (isAdmin) {
            Image(
                painter = painterResource(id = R.drawable.ic_crown_custom),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(1.dp, 1.dp) // used to make up for trasparent padding in icon
                    .size(LocalDimensions.current.badgeSize)
            )
        }
    }
}

@Preview
@Composable
fun PreviewAvatar() {
    PreviewTheme {
        Avatar(
            modifier = Modifier.padding(20.dp),
            isAdmin = true,
            accountId = AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1235")
        )
    }
}

@Composable
fun Avatar(
    recipient: Recipient,
    modifier: Modifier = Modifier,
    isAdmin: Boolean = false
) {
    BaseAvatar(
        modifier = modifier,
        isAdmin = isAdmin,
        update = {
            it.update(recipient)
        }
    )
}

@Composable
fun Avatar(
    userAddress: Address,
    modifier: Modifier = Modifier,
    isAdmin: Boolean = false
) {
    BaseAvatar(
        modifier = modifier,
        isAdmin = isAdmin,
        update = {
            it.update(userAddress)
        }
    )
}

@Composable
fun Avatar(
    accountId: AccountId,
    modifier: Modifier = Modifier,
    isAdmin: Boolean = false
) {
    Avatar(Address.fromSerialized(accountId.hexString),
        modifier = modifier,
        isAdmin = isAdmin
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
                    .background(backgroundColor, RoundedCornerShape(100))
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
                        .size(LocalDimensions.current.iconMedium)
                )

                Box(modifier = Modifier.weight(1f)) {
                    innerTextField()
                    if (query.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            color = LocalColors.current.textSecondary,
                            style = LocalType.current.xl
                        )
                    }
                }
            }
        },
        textStyle = LocalType.current.base.copy(color = LocalColors.current.text),
        modifier = modifier,
        cursorBrush = SolidColor(LocalColors.current.text)
    )
}

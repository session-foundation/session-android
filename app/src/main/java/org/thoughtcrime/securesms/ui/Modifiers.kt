package org.thoughtcrime.securesms.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions


/**
 * This is used to set the test tag that the QA team can use to retrieve an element in appium
 * In order to do so we need to set the testTagsAsResourceId to true, which ideally should be done only once
 * in the root composable, but our app is currently made up of  multiple isolated composables
 * set up in the old activity/fragment view system
 * As such we need to repeat it for every component that wants to use testTag, until such
 * a time as we have one root composable
 */
@Composable
fun Modifier.qaTag(tag: String?): Modifier {
    if (tag == null) return this
    return this.semantics { testTagsAsResourceId = true }.testTag(tag)
}

@Composable
fun Modifier.qaTag(@StringRes tagResId: Int?): Modifier {
    if (tagResId == null) return this
    return this.semantics { testTagsAsResourceId = true }.testTag(stringResource(tagResId))
}

@Composable
fun Modifier.border(
    shape: Shape = MaterialTheme.shapes.small
) = this.border(
    width = LocalDimensions.current.borderStroke,
    brush = SolidColor(LocalColors.current.borders),
    shape = shape
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

    return this
        .widthIn(max = LocalDimensions.current.maxContentWidth)
        .padding(
            horizontal = when {
                screenWidthDp < 380.dp -> smallExtraPadding
                else -> regularExtraPadding
            }
        )
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


/**
 * Creates a shimmer overlay effect that renders on top of existing content
 * @param color The base shimmer color (usually semi-transparent)
 * @param highlightColor The highlight color that moves across
 * @param animationDuration Duration of one shimmer cycle in milliseconds
 * @param delayBetweenCycles Delay between animation cycles in milliseconds (0 = continuous)
 */
fun Modifier.shimmerOverlay(
    color: Color = Color.White.copy(alpha = 0.0f),
    highlightColor: Color = Color.White.copy(alpha = 0.6f),
    animationDuration: Int = 1200,
    delayBetweenCycles: Int = 3000
): Modifier = composed {

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")

    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = animationDuration + delayBetweenCycles
                0f at 0
                1f at animationDuration
                1f at (animationDuration + delayBetweenCycles) // Hold at end during delay
            }
        ),
        label = "shimmerProgress"
    )

    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }
        .drawWithContent {
            // Draw the original content first
            drawContent()

            // Then draw the shimmer overlay on top
            drawShimmerOverlay(
                progress = shimmerProgress,
                color = color,
                highlightColor = highlightColor
            )
        }
}

private fun createShimmerBrush(
    progress: Float,
    color: Color,
    highlightColor: Color,
    width: Float,
    height: Float
): Brush {
    // Calculate the diagonal distance to ensure shimmer covers the entire component
    val diagonal = kotlin.math.sqrt(width * width + height * height)
    val shimmerWidth = diagonal * 0.3f // Width of the shimmer band

    // Start completely off-screen (left side) and end completely off-screen (right side)
    val totalDistance = width + shimmerWidth * 2
    val currentPosition = -shimmerWidth + (progress * totalDistance)

    return Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            color,
            highlightColor,
            color,
            Color.Transparent
        ),
        start = Offset(
            x = currentPosition - shimmerWidth,
            y = currentPosition - shimmerWidth
        ),
        end = Offset(
            x = currentPosition + shimmerWidth,
            y = currentPosition + shimmerWidth
        )
    )
}

private fun DrawScope.drawShimmerOverlay(
    progress: Float,
    color: Color,
    highlightColor: Color
) {
    val shimmerBrush = createShimmerBrush(
        progress = progress,
        color = color,
        highlightColor = highlightColor,
        width = size.width,
        height = size.height
    )

    // Use SrcAtop blend mode to only draw shimmer where content already exists
    drawRect(
        brush = shimmerBrush,
        size = size,
        blendMode = BlendMode.SrcAtop
    )
}


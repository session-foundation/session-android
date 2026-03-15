package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Constraints
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold

private const val MAX_COLLAPSED_LINE_COUNT = 25

/**
 * Message text with expandable content logic.
 *
 * Expansion state is controlled by the parent message row so it survives lazy-list
 * disposal/rebinding and can be shared across multiple text blocks in the same message.
 * The collapsed layout decides whether "Read more" is needed, and a full-text
 * measurement provides the growth delta so the parent list can preserve the
 * bubble's bottom edge during the same remeasure that expands the text.
 */
@Composable
fun ExpandableMessageText(
    text: AnnotatedString,
    isOutgoing: Boolean,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onUrlClick: ((String) -> Unit)? = null,
    onExpand: (Int) -> Unit = {},
) {
    val textColor = getTextColor(isOutgoing)
    val textMeasurer = rememberTextMeasurer()
    val readMoreLabel = stringResource(R.string.messageBubbleReadMore)
    val readMoreTextStyle = LocalType.current.base.bold().copy(color = textColor)
    val readMoreTopPaddingPx = with(LocalDensity.current) {
        LocalDimensions.current.xxsSpacing.roundToPx()
    }
    var collapsedLayout by remember(text, isOutgoing, onUrlClick != null) {
        mutableStateOf<TextLayoutResult?>(null)
    }

    val showsReadMore = !isExpanded && (collapsedLayout?.hasVisualOverflow == true)

    Column(modifier = modifier.width(IntrinsicSize.Max)) {
        MessageText(
            modifier = Modifier.fillMaxWidth()
            ,
            text = text,
            isOutgoing = isOutgoing,
            overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            maxLines = if (isExpanded) Int.MAX_VALUE else MAX_COLLAPSED_LINE_COUNT,
            onUrlClick = onUrlClick,
            onTextLayout = { layout ->
                if (!isExpanded) {
                    collapsedLayout = layout
                }
            }
        )

        if (showsReadMore) {
            Text(
                text = readMoreLabel,
                style = readMoreTextStyle,
                modifier = Modifier
                    .clickable {
                        val extraHeightPx = collapsedLayout?.let { layout ->
                            calculateExpandedTextDeltaPx(
                                collapsedLayout = layout,
                                maxWidthPx = layout.size.width,
                                textMeasurer = textMeasurer,
                                readMoreLabel = readMoreLabel,
                                readMoreTextStyle = readMoreTextStyle,
                                readMoreTopPaddingPx = readMoreTopPaddingPx,
                            )
                        } ?: 0

                        onExpand(extraHeightPx)
                    }
                    .padding(top = LocalDimensions.current.xxsSpacing)
            )
        }
    }
}

private fun calculateExpandedTextDeltaPx(
    collapsedLayout: TextLayoutResult,
    maxWidthPx: Int,
    textMeasurer: TextMeasurer,
    readMoreLabel: String,
    readMoreTextStyle: TextStyle,
    readMoreTopPaddingPx: Int,
): Int {
    if (maxWidthPx <= 0) return 0

    val input = collapsedLayout.layoutInput

    val expandedTextLayout = textMeasurer.measure(
        text = input.text,
        style = input.style,
        overflow = TextOverflow.Clip,
        softWrap = input.softWrap,
        maxLines = Int.MAX_VALUE,
        placeholders = input.placeholders,
        constraints = Constraints(maxWidth = maxWidthPx),
        layoutDirection = input.layoutDirection,
        density = input.density,
        fontFamilyResolver = input.fontFamilyResolver,
    )

    val readMoreLayout = textMeasurer.measure(
        text = AnnotatedString(readMoreLabel),
        style = readMoreTextStyle,
        constraints = Constraints(maxWidth = maxWidthPx),
        layoutDirection = input.layoutDirection,
        density = input.density,
        fontFamilyResolver = input.fontFamilyResolver,
    )

    return (
        expandedTextLayout.size.height -
            collapsedLayout.size.height -
            readMoreLayout.size.height -
            readMoreTopPaddingPx
        ).coerceAtLeast(0)
}


@Preview
@Composable
fun PreviewExpText(    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Box(Modifier.fillMaxSize()) {
            MessageBubble(
                modifier = Modifier,
                color = LocalColors.current.accent,
                content = {
                    ExpandableMessageText(
                        modifier = Modifier.padding(LocalDimensions.current.messageVerticalPadding),
                        text = AnnotatedString("1\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!\n" +
                                "!"),
                        isOutgoing = true,
                        isExpanded = false,
                    )
                }
            )
        }
    }
}

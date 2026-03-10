package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.bold

private const val MAX_COLLAPSED_LINE_COUNT = 25

/**
 * Message text with expandable content logic.
 *
 * Expansion state is controlled by the parent message row so it survives lazy-list
 * disposal/rebinding and can be shared across multiple text blocks in the same message.
 * This composable decides whether "Read more" is needed and computes the extra height
 * that the list controller needs when it requests the expanded row's new placement in
 * the reversed conversation list.
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
    var showsReadMore by remember(text) { mutableStateOf(false) }
    var textLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val textColor = getTextColor(isOutgoing)
    val textStyle = LocalType.current.large.copy(color = textColor)
    val readMoreLabel = stringResource(R.string.messageBubbleReadMore)
    val readMoreTextStyle = LocalType.current.base.bold().copy(color = textColor)
    val readMoreTopPaddingPx = with(LocalDensity.current) {
        LocalDimensions.current.xxsSpacing.roundToPx()
    }

    Column(modifier = modifier) {
        MessageText(
            text = text,
            isOutgoing = isOutgoing,
            overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            maxLines = if (isExpanded) Int.MAX_VALUE else MAX_COLLAPSED_LINE_COUNT,
            onUrlClick = onUrlClick,
            onTextLayout = { layout ->
                textLayout = layout
                showsReadMore = !isExpanded && layout.hasVisualOverflow
            }
        )

        if (!isExpanded && showsReadMore) {
            Text(
                text = readMoreLabel,
                style = readMoreTextStyle,
                modifier = Modifier
                    .clickable {
                        val extraHeightPx = textLayout?.let { layout ->
                            calculateExpandedTextDeltaPx(
                                text = text,
                                collapsedLayout = layout,
                                textMeasurer = textMeasurer,
                                textStyle = textStyle,
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
    text: AnnotatedString,
    collapsedLayout: TextLayoutResult,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    readMoreLabel: String,
    readMoreTextStyle: TextStyle,
    readMoreTopPaddingPx: Int,
): Int {
    val availableWidthPx = collapsedLayout.size.width
    if (availableWidthPx <= 0) return 0

    val fullTextLayout = textMeasurer.measure(
        text = text,
        style = textStyle,
        constraints = Constraints(maxWidth = availableWidthPx),
    )

    val readMoreLayout = textMeasurer.measure(
        text = AnnotatedString(readMoreLabel),
        style = readMoreTextStyle,
        constraints = Constraints(maxWidth = availableWidthPx),
    )

    return (
        fullTextLayout.size.height -
            collapsedLayout.size.height -
            readMoreLayout.size.height -
            readMoreTopPaddingPx
        ).coerceAtLeast(0)
}

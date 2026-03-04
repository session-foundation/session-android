package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalType

/**
 * Renders formatted message text with mention highlighting and optional link handling.
 * Pass [onUrlClick] to enable clickable, underlined links; pass null to render plain text.
 */
@Composable
fun MessageText(
    text: AnnotatedString,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    onUrlClick: ((String) -> Unit)? = null,
) {
    val colors = LocalColors.current

    val mainTextColor = getTextColor(isOutgoing)

    // Keep latest callback without rebuilding annotations on lambda identity changes
    val onUrlClickState = rememberUpdatedState(onUrlClick)

    val (displayText, bgRanges) =  remember(text, isOutgoing, colors, onUrlClick != null) {
        val withColors = buildAnnotatedString {
            append(text)

            val mentions = text.getStringAnnotations("mention_pk", 0, text.length)
            for (m in mentions) {
                val isSelf = text.getStringAnnotations("mention_self", m.start, m.end).isNotEmpty()

                // mention text color
                val fg = if (!isSelf && !isOutgoing) colors.accentText else colors.textBubbleSent

                addStyle(
                    SpanStyle(color = fg, fontWeight = FontWeight.Bold),
                    m.start,
                    m.end
                )
            }
        }

        val displayText = if (onUrlClick != null) {
            withColors.mapAnnotations { range ->
                val item = range.item
                if (item is LinkAnnotation.Clickable) {
                    val url = item.tag
                    AnnotatedString.Range(
                        item = LinkAnnotation.Clickable(
                            tag = url,
                            styles = item.styles,
                            linkInteractionListener = { onUrlClickState.value?.invoke(url) }
                        ),
                        start = range.start,
                        end = range.end,
                        tag = range.tag
                    )
                } else {
                    range
                }
            }
        } else {
            buildAnnotatedString {
                append(withColors.text)
                withColors.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
                withColors.paragraphStyles.forEach { addStyle(it.item, it.start, it.end) }
                for (ann in withColors.getStringAnnotations(0, withColors.length)) {
                    addStringAnnotation(ann.tag, ann.item, ann.start, ann.end)
                }
            }
        }

        val bgRanges = if (isOutgoing) emptyList()
        else displayText.getStringAnnotations("mention_bg", 0, displayText.length)

        displayText to bgRanges
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val density = LocalDensity.current
    // size and spacing for the mention highlight bg
    val cornerPx = with(density) { 6.dp.toPx() }
    val padHPx = with(density) { 4.dp.toPx() }
    val padVPx = with(density) { 3.dp.toPx() }

    val modifierWithBg =
        modifier.drawBehind {
            val lr = layout ?: return@drawBehind
            if (bgRanges.isEmpty()) return@drawBehind

            bgRanges.forEach { ann ->
                computeLineRectsForRange(lr, ann.start, ann.end).forEach { r ->
                    drawRoundRect(
                        color = colors.accent,
                        topLeft = Offset(r.left - padHPx, r.top - padVPx),
                        size = Size(
                            width = (r.right - r.left) + padHPx * 2,
                            height = (r.bottom - r.top) + padVPx * 2
                        ),
                        cornerRadius = CornerRadius(cornerPx, cornerPx)
                    )
                }
            }
        }

    Text(
        text = displayText,
        style = LocalType.current.large.copy(color = mainTextColor),
        modifier = modifierWithBg,
        onTextLayout = { layout = it },
        maxLines = maxLines,
        overflow = overflow
    )
}

/**
 * Fast per-line rects for [start, endExclusive) using typographic edges.
 * (Spacing around pill is handled by OUTSIDE_SPACE in formatter + pad in drawBehind.)
 */
private fun computeLineRectsForRange(
    layout: TextLayoutResult,
    start: Int,
    endExclusive: Int
): List<Rect> {
    if (start >= endExclusive) return emptyList()

    val textLen = layout.layoutInput.text.length
    val s = start.coerceIn(0, textLen)
    val e = endExclusive.coerceIn(0, textLen)
    if (s >= e) return emptyList()

    val last = (e - 1).coerceAtLeast(s)
    val startLine = layout.getLineForOffset(s)
    val endLine = layout.getLineForOffset(last)

    val out = ArrayList<Rect>(endLine - startLine + 1)

    for (line in startLine..endLine) {
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line, visibleEnd = true)

        val segStart = maxOf(s, lineStart)
        val segEnd = minOf(e, lineEnd)
        if (segStart >= segEnd) continue

        val left = layout.getHorizontalPosition(segStart, usePrimaryDirection = true)
        val right = layout.getHorizontalPosition(segEnd, usePrimaryDirection = true)

        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)

        out += Rect(
            left = minOf(left, right),
            top = top,
            right = maxOf(left, right),
            bottom = bottom
        )
    }

    return out
}
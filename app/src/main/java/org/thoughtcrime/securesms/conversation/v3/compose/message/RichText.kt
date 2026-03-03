package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalType

/**
 * Renders message text with:
 * - URL tap handling without ClickableText (tap -> offset -> "url" annotation)
 * - Underlined URLs (from formatter)
 * - Mention coloring + bold
 * - Rounded bg for "mention_bg" ranges (with spacing)
 */
@Composable
fun RichText(
    text: AnnotatedString,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    onUrlClick: ((String) -> Unit)? = null,
) {
    val colors = LocalColors.current
    val mainTextColor = if (isOutgoing) colors.textBubbleSent else colors.textBubbleReceived

    // Apply mention foreground styling (keep your rules; adjust as needed)
    val styled = remember(text, isOutgoing, mainTextColor, colors) {
        buildAnnotatedString {
            append(text)

            val mentions = text.getStringAnnotations("mention_pk", 0, text.length)
            for (m in mentions) {
                val isSelf = text.getStringAnnotations("mention_self", m.start, m.end)
                    .firstOrNull()?.item == "true"

                val fg = if (!isSelf && !isOutgoing) colors.accentText else colors.textBubbleSent

                addStyle(
                    SpanStyle(color = fg, fontWeight = FontWeight.Bold),
                    m.start,
                    m.end
                )
            }
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val density = LocalDensity.current
    val cornerPx = with(density) { 6.dp.toPx() }
    val padHPx = with(density) { 4.dp.toPx() }
    val padVPx = with(density) { 3.dp.toPx() }

    val bgRanges = remember(text, isOutgoing) {
        if (isOutgoing) emptyList()
        else text.getStringAnnotations("mention_bg", 0, text.length)
    }

    val modifierWithBgAndClicks =
        modifier
            .drawBehind {
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
            .pointerInput(text, onUrlClick) {
                if (onUrlClick == null) return@pointerInput
                detectTapGestures { pos ->
                    val lr = layout ?: return@detectTapGestures
                    val offset = lr.getOffsetForPosition(pos)

                    val hit = text.getStringAnnotations("url", offset, offset)
                        .firstOrNull()
                        ?.item
                        ?: return@detectTapGestures

                    onUrlClick(hit)
                }
            }

    Text(
        text = styled,
        style = LocalType.current.large.copy(color = mainTextColor),
        modifier = modifierWithBgAndClicks,
        onTextLayout = { layout = it },
        maxLines = maxLines,
        overflow = overflow
    )
}

private fun computeLineRectsForRange(
    layout: TextLayoutResult,
    start: Int,
    endExclusive: Int
): List<Rect> {
    if (start >= endExclusive) return emptyList()

    val rectsByLine = LinkedHashMap<Int, Rect>()
    val last = (endExclusive - 1).coerceAtLeast(start)

    for (offset in start..last) {
        val line = layout.getLineForOffset(offset)
        val box = layout.getBoundingBox(offset)

        val existing = rectsByLine[line]
        rectsByLine[line] = if (existing == null) {
            Rect(box.left, box.top, box.right, box.bottom)
        } else {
            Rect(
                left = minOf(existing.left, box.left),
                top = minOf(existing.top, box.top),
                right = maxOf(existing.right, box.right),
                bottom = maxOf(existing.bottom, box.bottom)
            )
        }
    }

    // Normalize to full line height so the bg looks consistent
    return rectsByLine.map { (line, r) ->
        val top = layout.getLineTop(line).toFloat()
        val bottom = layout.getLineBottom(line).toFloat()
        Rect(r.left, top, r.right, bottom)
    }
}
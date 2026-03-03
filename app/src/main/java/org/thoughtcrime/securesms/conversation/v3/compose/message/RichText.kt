package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextOverflow.Companion
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalType

/**
 * Renders message text with:
 * - clickable URLs (via LinkAnnotation inside AnnotatedString)
 * - underlined URLs (done in formatter via TextLinkStyles)
 * - mention bold (done in formatter)
 * - mention foreground color parity (applied here)
 * - mention rounded background parity (drawn here)
 */
@Composable
fun RichText(
    text: AnnotatedString,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = LocalColors.current

    // Parity with your XML color logic:
    // mainTextColor: default message text color for this bubble direction
    val mainTextColor = if (isOutgoing) colors.textBubbleSent else colors.textBubbleReceived


    // Build a styled copy (preserves existing LinkAnnotations)
    val styled = remember(text, isOutgoing, mainTextColor) {
        buildAnnotatedString {
            append(text)

            val mentions = text.getStringAnnotations("mention_pk", 0, text.length)
            for (m in mentions) {
                val isSelf = text.getStringAnnotations("mention_self", m.start, m.end)
                    .firstOrNull()?.item == "true"

                // Foreground parity rules :
                val fg = when {
                    // Incoming mentioning you: foreground is mainTextColor (on accent bg)
                    !isSelf && !isOutgoing -> colors.accent

                    else -> colors.textBubbleSent
                }

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
    val padVPx = with(density) { 2.dp.toPx() }

    // Mentions that need rounded background (parity tag from formatter)
    val bgRanges = remember(text, isOutgoing) {
        if (isOutgoing) emptyList()
        else text.getStringAnnotations("mention_bg", 0, text.length)
    }

    val modifierWithBg = modifier.drawBehind {
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

    // You can apply padding outside or pass in as modifier.
    Text(
        text = styled,
        style = LocalType.current.large.copy(color = mainTextColor),
        modifier = modifierWithBg,
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

    return rectsByLine.map { (line, r) ->
        val top = layout.getLineTop(line).toFloat()
        val bottom = layout.getLineBottom(line).toFloat()
        Rect(r.left, top, r.right, bottom)
    }
}
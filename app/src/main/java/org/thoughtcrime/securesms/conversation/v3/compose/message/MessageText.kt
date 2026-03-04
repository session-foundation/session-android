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
 *
 * Expects an [AnnotatedString] pre-processed by [MessageTextFormatter], which provides:
 * - Link annotations (clickable URLs with underline style)
 * - Mention metadata via string annotations ("mention_pk", "mention_self", "mention_bg")
 *
 * This composable then layers on:
 * - Mention foreground colors based on theme and message direction
 * - URL click handling (when [onUrlClick] is provided)
 * - Pill background drawing for self-mentions in incoming messages
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

    // Capture the latest callback in a ref so that the remember block below
    // doesn't need to recompute when only the lambda identity changes.
    val onUrlClickState = rememberUpdatedState(onUrlClick)

    // Single processing pass that:
    // 1. Applies mention foreground colors
    // 2. Wires up URL click handlers (if enabled) or strips link annotations (if disabled)
    // 3. Extracts pill background ranges for self-mentions
    //
    // Keyed on onUrlClick nullity (not identity) to avoid recomposition from lambda captures.
    val (displayText, bgRanges) = remember(text, isOutgoing, colors, onUrlClick != null) {

        // Step 1: Apply mention colors on top of the formatter's bold + metadata annotations
        val withColors = buildAnnotatedString {
            append(text)

            val mentions = text.getStringAnnotations("mention_pk", 0, text.length)
            for (m in mentions) {
                val isSelf = text.getStringAnnotations("mention_self", m.start, m.end).isNotEmpty()

                // Self-mentions and outgoing messages use the sent bubble text color;
                // other-mentions in incoming messages use the accent color for contrast.
                val fg = if (!isSelf && !isOutgoing) colors.accentText else colors.textBubbleSent

                addStyle(
                    SpanStyle(color = fg, fontWeight = FontWeight.Bold),
                    m.start,
                    m.end
                )
            }
        }

        // Step 2: Handle links based on whether click handling is enabled
        val displayText = if (onUrlClick != null) {
            // Replace the formatter's no-op link listeners with our actual click handler
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
            // Strip all link annotations (removing underlines and click behavior)
            // while preserving span styles, paragraph styles, and string annotations
            buildAnnotatedString {
                append(withColors.text)
                withColors.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
                withColors.paragraphStyles.forEach { addStyle(it.item, it.start, it.end) }
                for (ann in withColors.getStringAnnotations(0, withColors.length)) {
                    addStringAnnotation(ann.tag, ann.item, ann.start, ann.end)
                }
            }
        }

        // Step 3: Collect pill background ranges (only for incoming messages with self-mentions)
        val bgRanges = if (isOutgoing) emptyList()
        else displayText.getStringAnnotations("mention_bg", 0, displayText.length)

        displayText to bgRanges
    }

    // -- Pill background drawing --

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val density = LocalDensity.current
    val cornerPx = with(density) { 6.dp.toPx() }
    val padHPx = with(density) { 4.dp.toPx() }  // horizontal padding around pill
    val padVPx = with(density) { 3.dp.toPx() }  // vertical padding around pill

    // Draw rounded-rect pill backgrounds behind self-mention text ranges.
    // Uses the text layout result to compute per-line rects (handles line wrapping).
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
 * Computes per-line bounding rectangles for a text range within a [TextLayoutResult].
 *
 * When a mention spans multiple lines (e.g. due to wrapping), this returns one [Rect] per line
 * so that each segment gets its own pill background.
 *
 * Spacing around the pill is handled externally:
 * - Horizontal text spacing: OUTSIDE_SPACE characters inserted by [MessageTextFormatter]
 * - Visual padding: padH/padV applied in the drawBehind block above
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

    val startLine = layout.getLineForOffset(s)
    val endLine = layout.getLineForOffset((e - 1).coerceAtLeast(s))

    val out = ArrayList<Rect>(endLine - startLine + 1)

    for (line in startLine..endLine) {
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line, visibleEnd = true)

        // Clamp to the intersection of the mention range and this line
        val segStart = maxOf(s, lineStart)
        val segEnd = minOf(e, lineEnd)
        if (segStart >= segEnd) continue

        val left = layout.getHorizontalPosition(segStart, usePrimaryDirection = true)
        val right = layout.getHorizontalPosition(segEnd, usePrimaryDirection = true)
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)

        // min/max handles RTL where left > right
        out += Rect(
            left = minOf(left, right),
            top = top,
            right = maxOf(left, right),
            bottom = bottom
        )
    }

    return out
}
package org.session.libsession.utilities

import android.content.Context
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.View.TEXT_ALIGNMENT_CENTER
import android.view.View.TEXT_ALIGNMENT_TEXT_END
import android.view.View.TEXT_ALIGNMENT_VIEW_END
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt

@ColorInt
fun Context.getColorFromAttr(
    @AttrRes attrColor: Int,
    typedValue: TypedValue = TypedValue(),
    resolveRefs: Boolean = true
): Int {
    theme.resolveAttribute(attrColor, typedValue, resolveRefs)
    return typedValue.data
}

inline fun <reified LP : ViewGroup.LayoutParams> View.modifyLayoutParams(function: LP.() -> Unit) {
    layoutParams = (layoutParams as LP).apply { function() }
}

fun TextView.needsCollapsing(
    availableWidthPx: Int,
    maxLines: Int
): Boolean {
    // Pick the width the TextView will actually respect before draw
    val rawWidth = when {
        measuredWidth > 0 -> measuredWidth
        // if maxWidth is set, we check it
        maxWidth in 1 until Int.MAX_VALUE -> minOf(availableWidthPx, maxWidth)
        else -> availableWidthPx
    }
    val contentWidth = (rawWidth - paddingLeft - paddingRight).coerceAtLeast(0)
    if (contentWidth <= 0 || text.isNullOrEmpty()) return false

    val textForLayout = transformationMethod?.getTransformation(text, this) ?: text

    val alignment = when (textAlignment) {
        TEXT_ALIGNMENT_CENTER -> Layout.Alignment.ALIGN_CENTER
        TEXT_ALIGNMENT_VIEW_END, TEXT_ALIGNMENT_TEXT_END -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }
    val direction = when (textDirection) {
        View.TEXT_DIRECTION_FIRST_STRONG_RTL -> TextDirectionHeuristics.FIRSTSTRONG_RTL
        View.TEXT_DIRECTION_RTL -> TextDirectionHeuristics.RTL
        View.TEXT_DIRECTION_LTR -> TextDirectionHeuristics.LTR
        else -> TextDirectionHeuristics.FIRSTSTRONG_LTR
    }

    val builder = StaticLayout.Builder
        .obtain(textForLayout, 0, textForLayout.length, paint, contentWidth)
        .setIncludePad(includeFontPadding)
        .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
        .setBreakStrategy(breakStrategy)
        .setHyphenationFrequency(hyphenationFrequency)
        .setAlignment(alignment)
        .setTextDirection(direction)
        .setMaxLines(maxLines)                                   // cap at maxLines
        .setEllipsize(ellipsize ?: TextUtils.TruncateAt.END)     // compute ellipsis

    builder.setJustificationMode(justificationMode)

    val layout = builder.build()

    // Fewer than maxLines: definitely no truncation
    if (layout.lineCount < maxLines) return false
    // (Defensive) more than maxLines: truncated
    if (layout.lineCount > maxLines) return true

    // Exactly maxLines: truncated if last line is ellipsized or characters were cut
    val last = (maxLines - 1).coerceAtMost(layout.lineCount - 1)
    return layout.getEllipsisCount(last) > 0 || layout.getLineEnd(last) < textForLayout.length
}
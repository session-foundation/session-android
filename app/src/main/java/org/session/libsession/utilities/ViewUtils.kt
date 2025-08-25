package org.session.libsession.utilities

import android.content.Context
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.util.TypedValue
import android.view.View
import android.view.View.TEXT_ALIGNMENT_CENTER
import android.view.View.TEXT_ALIGNMENT_TEXT_END
import android.view.View.TEXT_ALIGNMENT_VIEW_END
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.core.widget.TextViewCompat

@ColorInt
fun Context.getColorFromAttr(
    @AttrRes attrColor: Int,
    typedValue: TypedValue = TypedValue(),
    resolveRefs: Boolean = true
): Int {
    theme.resolveAttribute(attrColor, typedValue, resolveRefs)
    return typedValue.data
}

inline fun <reified LP: ViewGroup.LayoutParams> View.modifyLayoutParams(function: LP.() -> Unit) {
    layoutParams = (layoutParams as LP).apply { function() }
}

fun TextView.needsCollapsing(
    availableWidthPx: Int,
    maxLines: Int
): Boolean {
    if (availableWidthPx <= 0 || text.isNullOrEmpty()) return false

    // The exact text that will be drawn (all-caps, password dots …)
    val textForLayout = transformationMethod?.getTransformation(text, this) ?: text

    // Build a StaticLayout that mirrors this TextView’s wrap rules
    val builder = StaticLayout.Builder
        .obtain(textForLayout, 0, textForLayout.length, paint, availableWidthPx)
        .setIncludePad(includeFontPadding)
        .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
        .setBreakStrategy(breakStrategy)          // API 23+
        .setHyphenationFrequency(hyphenationFrequency)
        .setMaxLines(Int.MAX_VALUE)

    // Alignment (honours RTL if textAlignment is END/VIEW_END)
    builder.setAlignment(
        when (textAlignment) {
            TEXT_ALIGNMENT_CENTER                    -> Layout.Alignment.ALIGN_CENTER
            TEXT_ALIGNMENT_VIEW_END,
            TEXT_ALIGNMENT_TEXT_END                 -> Layout.Alignment.ALIGN_OPPOSITE
            else                                    -> Layout.Alignment.ALIGN_NORMAL
        }
    )

    // Direction heuristic
    val dir = when (textDirection) {
        View.TEXT_DIRECTION_FIRST_STRONG_RTL -> TextDirectionHeuristics.FIRSTSTRONG_RTL
        View.TEXT_DIRECTION_RTL              -> TextDirectionHeuristics.RTL
        View.TEXT_DIRECTION_LTR              -> TextDirectionHeuristics.LTR
        else                                 -> TextDirectionHeuristics.FIRSTSTRONG_LTR
    }
    builder.setTextDirection(dir)

    builder.setEllipsize(ellipsize)

    builder.setJustificationMode(justificationMode)

    val layout = builder.build()
    return layout.lineCount > maxLines
}
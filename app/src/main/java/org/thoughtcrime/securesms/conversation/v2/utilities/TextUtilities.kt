package org.thoughtcrime.securesms.conversation.v2.utilities

import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.text.getSpans
import androidx.core.text.toSpannable

object TextUtilities {

    fun TextView.getIntersectedModalSpans(event: MotionEvent): List<ModalURLSpan> {
        val xInt = event.rawX.toInt()
        val yInt = event.rawY.toInt()
        val hitRect = Rect(xInt, yInt, xInt, yInt)
        return getIntersectedModalSpans(hitRect)
    }

    fun TextView.getIntersectedModalSpans(hitRect: Rect): List<ModalURLSpan> {
        val textLayout = layout ?: return emptyList()
        val lineRect = Rect()
        val offset = intArrayOf(0, 0).also { getLocationOnScreen(it) }
        val textSpannable = text.toSpannable()
        return (0 until textLayout.lineCount).flatMap { line ->
            textLayout.getLineBounds(line, lineRect)
            lineRect.offset(offset[0] + totalPaddingLeft, offset[1] + totalPaddingTop)
            if (lineRect.contains(hitRect)) {
                // calculate the url span intersected with (if any)
                val off = textLayout.getOffsetForHorizontal(line, hitRect.left.toFloat()) // left and right will be the same
                textSpannable.getSpans<ModalURLSpan>(off, off).toList()
            } else {
                emptyList()
            }
        }
    }

    fun String.textSizeInBytes(): Int = this.toByteArray(Charsets.UTF_8).size


}
package org.thoughtcrime.securesms.util

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ImageSpan

class PaddedImageSpan(
    drawable: Drawable,
    verticalAlignment: Int,
    private val paddingTop: Int
) : ImageSpan(drawable, verticalAlignment) {

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val drawable = drawable
        canvas.save()

        // Adjust the image's vertical position by adding the top padding
        val transY = top + paddingTop

        canvas.translate(x, transY.toFloat())
        drawable.draw(canvas)
        canvas.restore()
    }
}
package org.thoughtcrime.securesms.util

import android.content.res.Resources
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

fun toPx(dp: Int, resources: Resources): Int {
    return toPx(dp.toFloat(), resources).roundToInt()
}

fun toPx(dp: Float, resources: Resources): Float {
    val scale = resources.displayMetrics.density
    return (dp * scale)
}

fun toDp(px: Int, resources: Resources): Int {
    return toDp(px.toFloat(), resources).roundToInt()
}

fun toDp(px: Float, resources: Resources): Float {
    val scale = resources.displayMetrics.density
    return (px / scale)
}

/**
 * Returns true if the recyclerview is scrolled within 50dp of the bottom
 */
val RecyclerView.isNearBottom: Boolean
    get() = computeVerticalScrollOffset().coerceAtLeast(0) +
            computeVerticalScrollExtent() +
            toPx(50, resources) >= computeVerticalScrollRange()

val RecyclerView.isFullyScrolled: Boolean
    get() {
        return scrollAmount == 0
    }

val RecyclerView.scrollAmount: Int
    get() {
        val scrollOffset = computeVerticalScrollOffset().coerceAtLeast(0)
        val scrollExtent = computeVerticalScrollExtent()
        val scrollRange = computeVerticalScrollRange()


        // We're at the bottom if the offset + extent equals the range
        return scrollOffset + scrollExtent - scrollRange
    }


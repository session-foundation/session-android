package org.thoughtcrime.securesms.util

import android.content.res.Resources
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
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

val RecyclerView.isNearBottom: Boolean
    get() {
        val offset = computeVerticalScrollOffset().coerceAtLeast(0)
        val extent = computeVerticalScrollExtent()
        val range = computeVerticalScrollRange()
        val thresholdPx = toPx(50, resources)

        // If there's no scrollable area, don't treat it as "near bottom"
        if (range <= extent) {
            return false
        }

        val remaining = range - (offset + extent) // distance from bottom in px

        // true only when remaining distance to bottom <= 50dp
        return remaining <= thresholdPx
    }

val RecyclerView.isFullyScrolled: Boolean
    get() {
        return (layoutManager as LinearLayoutManager).findLastCompletelyVisibleItemPosition() ==
                adapter!!.itemCount - 1
    }

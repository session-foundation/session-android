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

val RecyclerView.isScrolledToBottom: Boolean
    get() = computeVerticalScrollOffset().coerceAtLeast(0) +
            computeVerticalScrollExtent() +
            toPx(50, resources) >= computeVerticalScrollRange()

val RecyclerView.isScrolledToWithin30dpOfBottom: Boolean
    get() = computeVerticalScrollOffset().coerceAtLeast(0) +
            computeVerticalScrollExtent() +
            toPx(30, resources) >= computeVerticalScrollRange()
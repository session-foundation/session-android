package org.thoughtcrime.securesms.util

import android.content.res.Resources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    get() {
        // Retrieve the bottom inset from the window insets, if available.
        val bottomInset = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0

        return computeVerticalScrollOffset().coerceAtLeast(0) +
                computeVerticalScrollExtent() +
                toPx(30, resources) +
                bottomInset >= computeVerticalScrollRange()
    }


val RecyclerView.isFullyScrolled: Boolean
    get() {
        val scrollOffset = computeVerticalScrollOffset().coerceAtLeast(0)
        val scrollExtent = computeVerticalScrollExtent()
        val scrollRange = computeVerticalScrollRange()

        /// Retrieve the bottom inset from the window insets, if available.
        val bottomInset = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0

        // We're at the bottom if the offset + extent equals the range (accounting for insets)
        return scrollOffset + scrollExtent >= scrollRange - bottomInset
    }

val RecyclerView.scrollAmount: Int
    get() {
        val scrollOffset = computeVerticalScrollOffset().coerceAtLeast(0)
        val scrollExtent = computeVerticalScrollExtent()
        val scrollRange = computeVerticalScrollRange()


        // We're at the bottom if the offset + extent equals the range
        return scrollOffset + scrollExtent - scrollRange
    }


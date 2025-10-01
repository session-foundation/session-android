package org.thoughtcrime.securesms.util.adapter

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

// Makes sure that the recyclerView is scrolled to the bottom
fun RecyclerView.applyImeBottomPadding() {
    clipToPadding = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val system = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        v.updatePadding(bottom = max(system, ime))
        insets
    }
}

// Handle scroll logic
fun RecyclerView.handleScrollToBottom(fastScroll: Boolean = false) {
    val layoutManager = this.layoutManager as LinearLayoutManager
    val last = this.adapter?.itemCount?.minus(1)?.coerceAtLeast(0) ?: return

    if (last < 0) return

    val bottomOffset = this.paddingBottom

    if (layoutManager.isSmoothScrolling || fastScroll) {
        // second tap = instant align
        layoutManager.scrollToPositionWithOffset(last, bottomOffset)
        return
    }

    val scroller = object : LinearSmoothScroller(this.context) {
        override fun getVerticalSnapPreference() = SNAP_TO_END
        override fun calculateDtToFit(
            viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int
        ): Int {
            // default SNAP_TO_END is (boxEnd - viewEnd); subtract padding so it clears the IME
            return (boxEnd - viewEnd) - bottomOffset
        }
    }

    scroller.targetPosition = last
    layoutManager.startSmoothScroll(scroller)
}

fun RecyclerView.runWhenLaidOut(block: () -> Unit) {
    if (isLaidOut && !isLayoutRequested) {
        post(block)
    } else {
        doOnPreDraw { post(block) }
    }
}
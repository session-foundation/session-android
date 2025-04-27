package org.thoughtcrime.securesms.components

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * A FrameLayout that allows you to observe touch events dispatched to it.
 *
 * Note: this is different from [android.view.View.setOnTouchListener] as it allows you to observe the touch events
 * that flow through this parent regardless of whether they are consumed by child views.
 */
class ObservableTouchEventFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    var onTouchDispatchListener: OnTouchListener? = null

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        onTouchDispatchListener?.onTouch(this, ev)
        return super.dispatchTouchEvent(ev)
    }
}

package org.thoughtcrime.securesms.ui.components

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A FrameLayout that always leaves [verticalSpace] amount of vertical space for the parent view. In
 * other words, it will always measures itself `parentHeight - [verticalSpace]` as its height.
 *
 * This only works if parent asks us to measure own height while exposing the max.
 *
 * If, for example, a scrollable view is the parent, given it expects its children to measure
 * themselves in a unconstrained way, we won't know how much space we have to leave for the parent.
 */
class VerticalSpaceSavingFrameLayout @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attributeSet, defStyle) {

    var verticalSpace: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val newHeightMeasureSpec =
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
                val height = MeasureSpec.getSize(heightMeasureSpec)
                MeasureSpec.makeMeasureSpec(
                    (height - verticalSpace).coerceAtLeast(0),
                    MeasureSpec.EXACTLY
                )
            } else {
                heightMeasureSpec
            }

        super.onMeasure(widthMeasureSpec, newHeightMeasureSpec)
    }
}
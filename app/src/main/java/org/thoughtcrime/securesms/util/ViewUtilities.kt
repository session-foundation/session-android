package org.thoughtcrime.securesms.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.FloatEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.core.graphics.Insets
import androidx.core.graphics.applyCanvas
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.InsetsType
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import network.loki.messenger.R
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsignal.utilities.Log
import kotlin.math.roundToInt

fun View.contains(point: PointF): Boolean {
    return hitRect.contains(point.x.toInt(), point.y.toInt())
}

val View.hitRect: Rect
    get()  {
        val rect = Rect()
        getHitRect(rect)
        return rect
    }

@ColorInt
fun Context.getAccentColor() = getColorFromAttr(R.attr.colorAccent)

// Method to grab the appropriate attribute for a message colour.
// Note: This is an attribute, NOT a resource Id - see `getColorResourceIdFromAttr` for that.
@AttrRes
fun getMessageTextColourAttr(messageIsOutgoing: Boolean): Int {
    return if (messageIsOutgoing) R.attr.message_sent_text_color else R.attr.message_received_text_color
}

// Method to get an actual R.id.<SOME_COLOUR> resource Id from an attribute such as R.attr.message_sent_text_color etc.
@ColorRes
fun getColorResourceIdFromAttr(context: Context, attr: Int): Int {
    val outTypedValue = TypedValue()
    val successfullyFoundAttribute = context.theme.resolveAttribute(attr, outTypedValue, true)
    if (successfullyFoundAttribute) { return outTypedValue.resourceId }

    Log.w("ViewUtils", "Could not find colour attribute $attr in theme - using grey as a safe fallback")
    return R.color.gray50
}

fun View.animateSizeChange(@DimenRes startSizeID: Int, @DimenRes endSizeID: Int, animationDuration: Long = 250) {
    val startSize = resources.getDimension(startSizeID)
    val endSize = resources.getDimension(endSizeID)
    animateSizeChange(startSize, endSize)
}

fun View.animateSizeChange(startSize: Float, endSize: Float, animationDuration: Long = 250) {
    val layoutParams = this.layoutParams
    val animation = ValueAnimator.ofObject(FloatEvaluator(), startSize, endSize)
    animation.duration = animationDuration
    animation.addUpdateListener { animator ->
        val size = animator.animatedValue as Float
        layoutParams.width = size.toInt()
        layoutParams.height = size.toInt()
        this.layoutParams = layoutParams
    }
    animation.start()
}

fun View.fadeIn(duration: Long = 150) {
    alpha = 0.0f
    visibility = View.VISIBLE
    animate().setDuration(duration).alpha(1.0f).setListener(null).start()
}

fun View.fadeOut(duration: Long = 150) {
    animate().setDuration(duration).alpha(0.0f).setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            visibility = View.GONE
        }
    })
}

fun View.hideKeyboard() {
    val imm = this.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(this.windowToken, 0)
}

fun View.drawToBitmap(config: Bitmap.Config = Bitmap.Config.ARGB_8888, longestWidth: Int = 2000): Bitmap {
    val size = Size(measuredWidth, measuredHeight).coerceAtMost(longestWidth)
    val scale = size.width / measuredWidth.toFloat()

    return Bitmap.createBitmap(size.width, size.height, config).applyCanvas {
        scale(scale, scale)
        translate(-scrollX.toFloat(), -scrollY.toFloat())
        draw(this)
    }
}

fun Size.coerceAtMost(longestWidth: Int): Size =
    (width.toFloat() / height).let { aspect ->
        if (aspect > 1) {
            width.coerceAtMost(longestWidth).let { Size(it, (it / aspect).roundToInt()) }
        } else {
            height.coerceAtMost(longestWidth).let { Size((it * aspect).roundToInt(), it) }
        }
    }

fun EditText.addTextChangedListener(listener: (String) -> Unit) {
    addTextChangedListener(object: SimpleTextWatcher() {
        override fun onTextChanged(text: String) {
            listener(text)
        }
    })
}

/**
 * Applies the system insets to the view's paddings.
 */
@JvmOverloads
fun View.applySafeInsetsPaddings(
    @InsetsType
    typeMask: Int = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
    consumeInsets: Boolean = true,
    applyTop: Boolean = true,
    applyBottom: Boolean = true,
    alsoApply: (Insets) -> Unit = {}
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(typeMask)

        view.updatePadding(
            left = insets.left,
            top = if(applyTop) insets.top else 0,
            right = insets.right,
            bottom = if(applyBottom) insets.bottom else 0
        )

        alsoApply(insets)

        if (consumeInsets) {
            windowInsets.inset(insets)
        } else {
            // Return the insets unconsumed
            windowInsets
        }
    }
}

/**
 * Applies the system insets to the view's margins.
 */
@JvmOverloads
fun View.applySafeInsetsMargins(
    consumeInsets: Boolean = true,
    @InsetsType
    typeMask: Int = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        // Get system bars insets
        val systemBarsInsets = windowInsets.getInsets(typeMask)

        // Update view margins to account for system bars
        val lp = view.layoutParams as? MarginLayoutParams
        if (lp != null) {
            lp.setMargins(systemBarsInsets.left, systemBarsInsets.top, systemBarsInsets.right, systemBarsInsets.bottom)
            view.layoutParams = lp

            if (consumeInsets) {
                WindowInsetsCompat.CONSUMED
            } else {
                // Return the insets unconsumed
                windowInsets
            }
        } else {
            Log.w("ViewUtils", "Cannot apply insets to view with no margins")
            windowInsets
        }
    }
}

/**
 * Applies the system insets to a RecyclerView or ScrollView. The inset will apply as margin
 * at the top and padding at the bottom. For ScrollView, the bottom insets will be applied to the first child.
 */
@JvmOverloads
fun applyCommonWindowInsetsOnViews(
    mainRecyclerView: RecyclerView? = null,
    mainScrollView: ScrollView? = null
) {
    if (mainRecyclerView != null && mainScrollView == null) {
        mainRecyclerView.clipToPadding = false

        ViewCompat.setOnApplyWindowInsetsListener(mainRecyclerView) { _, windowInsets ->
            mainRecyclerView.updateLayoutParams<MarginLayoutParams> {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                topMargin = insets.top
                leftMargin = insets.left
                rightMargin = insets.right
            }

            mainRecyclerView.updatePadding(
                bottom = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
            )

            WindowInsetsCompat.CONSUMED
        }
    } else if (mainScrollView != null && mainRecyclerView == null) {
        val firstChild = requireNotNull(mainScrollView.getChildAt(0)) {
            "Given scrollView has no child to apply insets to"
        }

        ViewCompat.setOnApplyWindowInsetsListener(mainScrollView) { _, windowInsets ->
            mainScrollView.updateLayoutParams<MarginLayoutParams> {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                topMargin = insets.top
                leftMargin = insets.left
                rightMargin = insets.right
            }

            firstChild.updatePadding(
                bottom = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
            )

            WindowInsetsCompat.CONSUMED
        }
    } else {
        error("Either mainRecyclerView or mainScrollView must be non-null, but not both.")
    }
}

// Listener class that only accepts clicks at given interval to prevent button spam - can be used instead
// of a standard `onClickListener` in many places. A separate mechanism exists for VisibleMessageViews to
// prevent interfering with gestures.
fun View.setSafeOnClickListener(clickIntervalMS: Long = 1000L, onSafeClick: (View) -> Unit) {
    val safeClickListener = SafeClickListener(minimumClickIntervalMS = clickIntervalMS) {
        onSafeClick(it)
    }
    setOnClickListener(safeClickListener)
}
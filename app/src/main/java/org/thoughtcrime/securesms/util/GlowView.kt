package org.thoughtcrime.securesms.util

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.annotation.ColorInt
import network.loki.messenger.R
import kotlin.math.roundToInt

interface GlowView {
    var mainColor: Int
    var sessionShadowColor: Int
}

object GlowViewUtilities {

    fun animateColorChange(view: GlowView, @ColorInt startColor: Int, @ColorInt endColor: Int) {
        val animation = ValueAnimator.ofObject(ArgbEvaluator(), startColor, endColor)
        animation.duration = 250
        animation.addUpdateListener { animator ->
            val color = animator.animatedValue as Int
            view.mainColor = color
        }
        animation.start()
    }

    fun animateShadowColorChange(
        view: GlowView,
        @ColorInt startColor: Int,
        @ColorInt endColor: Int,
        duration: Long = 250
    ) {
        val animation = ValueAnimator.ofObject(ArgbEvaluator(), startColor, endColor)
        animation.duration = duration
        animation.interpolator = AccelerateDecelerateInterpolator()
        animation.addUpdateListener { animator ->
            val color = animator.animatedValue as Int
            view.sessionShadowColor = color
        }
        animation.start()
    }
}

class PNModeView : LinearLayout, GlowView {
    @ColorInt override var mainColor: Int = 0
        set(newValue) { field = newValue; paint.color = newValue }
    @ColorInt var strokeColor: Int = 0
        set(newValue) { field = newValue; strokePaint.color = newValue }
    @ColorInt override var sessionShadowColor: Int = 0
        set(newValue) { field = newValue; paint.setShadowLayer(toPx(4, resources).toFloat(), 0.0f, 0.0f, newValue) }

    private val paint: Paint by lazy {
        val result = Paint()
        result.style = Paint.Style.FILL
        result.isAntiAlias = true
        result
    }

    private val strokePaint: Paint by lazy {
        val result = Paint()
        result.style = Paint.Style.STROKE
        result.isAntiAlias = true
        result.strokeWidth = toPx(1, resources).toFloat()
        result
    }

    // region Lifecycle
    constructor(context: Context) : super(context) { }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { }

    init {
        setWillNotDraw(false)
    }
    // endregion

    // region Updating
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = resources.getDimension(R.dimen.pn_option_corner_radius)
        c.drawRoundRect(0.0f, 0.0f, w, h, r, r, paint)
        c.drawRoundRect(0.0f, 0.0f, w, h, r, r, strokePaint)
        super.onDraw(c)
    }
    // endregion
}

class StartConversationButtonImageView : androidx.appcompat.widget.AppCompatImageView, GlowView {
    @ColorInt override var mainColor: Int = 0
    set(newValue) { field = newValue; paint.color = newValue }
    @ColorInt override var sessionShadowColor: Int = 0
    set(newValue) { field = newValue; paint.setShadowLayer(toPx(6, resources).toFloat(), 0.0f, 0.0f, newValue) }

    private val paint: Paint by lazy {
        val result = Paint()
        result.style = Paint.Style.FILL
        result.isAntiAlias = true
        result
    }

    // region Lifecycle
    constructor(context: Context) : super(context) { }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { }

    init {
        setWillNotDraw(false)
    }
    // endregion

    // region Updating
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawCircle(w / 2, h / 2, w / 2, paint)
        super.onDraw(c)
    }
    // endregion
}

class PathDotView : View, GlowView {
    @ColorInt override var mainColor: Int = 0
        set(newValue) { field = newValue; paint.color = newValue }
    @ColorInt override var sessionShadowColor: Int = 0
        set(newValue) { field = newValue; paint.setShadowLayer(toPx(4, resources).toFloat(), 0.0f, 0.0f, newValue) }

    private val paint: Paint by lazy {
        val result = Paint()
        result.style = Paint.Style.FILL
        result.isAntiAlias = true
        result
    }

    // region Lifecycle
    constructor(context: Context) : super(context) { }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { }

    init {
        setWillNotDraw(false)
    }
    // endregion

    // region Updating
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawCircle(w / 2, h / 2, w / 2, paint)
        super.onDraw(c)
    }
    // endregion
}

class InputBarButtonImageViewContainer : RelativeLayout, GlowView {
    @ColorInt override var mainColor: Int = 0
        set(newValue) { field = newValue; fillPaint.color = newValue }
    @ColorInt var strokeColor: Int = 0
        set(newValue) { field = newValue; strokePaint.color = newValue }
    @ColorInt override var sessionShadowColor: Int = 0 // Unused

    private val fillPaint: Paint by lazy {
        val result = Paint()
        result.style = Paint.Style.FILL
        result.isAntiAlias = true
        result
    }

    private val strokePaint: Paint by lazy {
        val result = Paint()
        result.style = Paint.Style.STROKE
        result.isAntiAlias = true
        result.strokeWidth = 1.0f
        result.alpha = (255 * 0.2f).roundToInt()
        result
    }

    // region Lifecycle
    constructor(context: Context) : super(context) { }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { }

    init {
        setWillNotDraw(false)
    }
    // endregion

    // region Updating
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawCircle(w / 2, h / 2, w / 2, fillPaint)
        if (strokeColor != 0) {
            c.drawCircle(w / 2, h / 2, w / 2, strokePaint)
        }
        super.onDraw(c)
    }
    // endregion
}

class MessageBubbleView : androidx.constraintlayout.widget.ConstraintLayout, GlowView {
    @ColorInt override var mainColor: Int = 0
        set(newValue) { field = newValue; paint.color = newValue }
    @ColorInt override var sessionShadowColor: Int = 0
        set(newValue) {
            field = newValue
            shadowPaint.setShadowLayer(toPx(10, resources).toFloat(), 0.0f, 0.0f, newValue)

            if (numShadowRenders == 0) {
                numShadowRenders = 1
            }

            invalidate()
        }
    var cornerRadius: Float = 0f
    var numShadowRenders: Int = 0

    private val paint: Paint by lazy {
        val result = Paint()
        result.style = Paint.Style.FILL
        result.isAntiAlias = true
        result
    }

    private val shadowPaint: Paint by lazy {
        val result = Paint()
        result.style = Paint.Style.FILL
        result.isAntiAlias = true
        result
    }

    // region Lifecycle
    constructor(context: Context) : super(context) { }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { }

    init {
        setWillNotDraw(false)
    }
    // endregion

    // region Updating
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        (0 until numShadowRenders).forEach {
            c.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, shadowPaint)
        }

        c.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, paint)
        super.onDraw(c)
    }
    // endregion
}

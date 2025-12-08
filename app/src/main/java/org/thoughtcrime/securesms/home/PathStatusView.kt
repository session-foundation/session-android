package org.thoughtcrime.securesms.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.utilities.getColorFromAttr
import org.thoughtcrime.securesms.util.toPx

class PathStatusView : View {
    @ColorInt var mainColor: Int = 0
        set(newValue) { field = newValue; paint.color = newValue }
    @ColorInt var sessionShadowColor: Int = 0
        set(newValue) { field = newValue; paint.setShadowLayer(toPx(8, resources).toFloat(), 0.0f, 0.0f, newValue) }

    private val paint: Paint by lazy {
        val result = Paint()
        result.style = Paint.Style.FILL
        result.isAntiAlias = true
        result
    }

    private val unknownColor by lazy {
        ContextCompat.getColor(context, R.color.classic_dark_4)
    }

    private val validColor by lazy {
        ContextCompat.getColor(context, R.color.accent_green)
    }

    private val buildingColor by lazy {
        context.getColorFromAttr(R.attr.warning)
    }

    private val errorColor by lazy {
        context.getColorFromAttr(R.attr.danger)
    }

    private var updateJob: Job? = null

    constructor(context: Context) : super(context) {
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        initialize()
    }

    private fun initialize() {
        setWillNotDraw(false)
        //set the unknown color until we have data
        mainColor = unknownColor
        sessionShadowColor = unknownColor
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        updateJob = GlobalScope.launch {
            OnionRequestAPI.pathStatus
                .collectLatest { status ->
                    withContext(Dispatchers.Main) {
                        when (status) {
                            OnionRequestAPI.PathStatus.READY -> {
                                mainColor = validColor
                                sessionShadowColor = validColor
                            }

                            OnionRequestAPI.PathStatus.BUILDING -> {
                                mainColor = buildingColor
                                sessionShadowColor = buildingColor
                            }

                            OnionRequestAPI.PathStatus.ERROR -> {
                                mainColor = errorColor
                                sessionShadowColor = errorColor
                            }
                        }

                        invalidate()
                    }
                }
        }
    }


    override fun onDetachedFromWindow() {
        updateJob?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawCircle(w / 2, h / 2, w / 2, paint)
        super.onDraw(c)
    }
}
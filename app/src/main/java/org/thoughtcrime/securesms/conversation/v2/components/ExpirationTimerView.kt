package org.thoughtcrime.securesms.conversation.v2.components

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import network.loki.messenger.R
import org.session.libsession.snode.SnodeAPI.nowWithOffset
import kotlin.math.round

class ExpirationTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private val frames = intArrayOf(
        R.drawable.ic_clock_0,
        R.drawable.ic_clock_1,
        R.drawable.ic_clock_2,
        R.drawable.ic_clock_3,
        R.drawable.ic_clock_4,
        R.drawable.ic_clock_5,
        R.drawable.ic_clock_6,
        R.drawable.ic_clock_7,
        R.drawable.ic_clock_8,
        R.drawable.ic_clock_9,
        R.drawable.ic_clock_10,
        R.drawable.ic_clock_11,
        R.drawable.ic_clock_12
    )

    fun setTimerIcon() {
        setExpirationTime(0L, 0L)
    }

    fun setExpirationTime(startedAt: Long, expiresIn: Long) {
        if (expiresIn == 0L) {
            setImageResource(R.drawable.ic_clock_11)
            return
        }

        if (startedAt == 0L) {
            // timer has not started
            setImageResource(R.drawable.ic_clock_12)
            return
        }

        val elapsedTime = nowWithOffset - startedAt
        val remainingTime = expiresIn - elapsedTime
        val remainingPercent = (remainingTime / expiresIn.toFloat()).coerceIn(0f, 1f)

        val frameCount = round(frames.size * remainingPercent).toInt().coerceIn(1, frames.size)
        val frameTime = round(remainingTime / frameCount.toFloat()).toInt()

        AnimationDrawable().apply {
            frames.take(frameCount).reversed().forEach { addFrame(ContextCompat.getDrawable(context, it)!!, frameTime) }
            isOneShot = true
        }.also(::setImageDrawable).apply(AnimationDrawable::start)
    }
}

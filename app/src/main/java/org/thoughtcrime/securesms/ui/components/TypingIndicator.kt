package org.thoughtcrime.securesms.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

private const val CYCLE_DURATION_MS = 1500
private const val DOT_DURATION_MS = 600
private const val FADE_HALF_MS = DOT_DURATION_MS / 2 // 300
private const val DOT1_OFFSET_MS = 0
private const val DOT2_OFFSET_MS = 150
private const val DOT3_OFFSET_MS = 300

private const val MIN_ALPHA = 0.5f
private const val MIN_SCALE = 0.75f

@Composable
fun TypingIndicator(
    isTyping: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    dotSize: Dp = LocalDimensions.current.shapeXXSmall,
    dotSpacing: Dp = 2.dp,
) {
    Row(
        modifier = modifier
            .background(
                color = LocalColors.current.backgroundBubbleReceived,
                shape = RoundedCornerShape(18.dp)
            )
            .padding( 6.dp),
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
    ) {
        TypingDot(
            active = isTyping,
            tint = tint,
            dotSize = dotSize,
            offsetMs = DOT1_OFFSET_MS
        )
        TypingDot(
            active = isTyping,
            tint = tint,
            dotSize = dotSize,
            offsetMs = DOT2_OFFSET_MS
        )
        TypingDot(
            active = isTyping,
            tint = tint,
            dotSize = dotSize,
            offsetMs = DOT3_OFFSET_MS
        )
    }
}

@Composable
private fun TypingDot(
    active: Boolean,
    tint: Color,
    dotSize: Dp,
    offsetMs: Int,
) {
    // phase: 0 -> 1 -> 0 during the dot’s 600ms active window, otherwise stays 0.
    val phase = if (active) {
        val transition = rememberInfiniteTransition(label = "typingIndicator")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = CYCLE_DURATION_MS

                    // default (inactive in this cycle)
                    0f at 0

                    // stay at 0 until this dot’s window starts
                    0f at offsetMs

                    // fade/scale up for 300ms
                    1f at (offsetMs + FADE_HALF_MS)

                    // fade/scale down for 300ms (back to 0 at end of the 600ms window)
                    0f at (offsetMs + DOT_DURATION_MS)

                    // remain at 0 until end of cycle
                    0f at CYCLE_DURATION_MS
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dotPhase_$offsetMs"
        )
        p
    } else {
        0f
    }

    val alpha = MIN_ALPHA + (1f - MIN_ALPHA) * phase
    val scale = MIN_SCALE + (1f - MIN_SCALE) * phase

    Box(
        modifier = Modifier
            .size(dotSize)
            .graphicsLayer {
                this.alpha = alpha
                this.scaleX = scale
                this.scaleY = scale
            }
            .clip(CircleShape)
            .background(tint)
    )
}
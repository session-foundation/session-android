package org.thoughtcrime.securesms.conversation.v3.compose.message

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

@Composable
fun rememberHighlightAlpha(
    messageId: MessageId,
    trigger: HighlightMessage?,
): Float {
    val alphaAnim = remember { Animatable(0f) }
    val triggerToken = trigger?.token
    val defaultConsumedToken = remember(messageId) { Long.MIN_VALUE }
    val lastConsumedToken = rememberSaveable(messageId) { androidx.compose.runtime.mutableLongStateOf(defaultConsumedToken) }

    LaunchedEffect(triggerToken) {
        val token = triggerToken ?: return@LaunchedEffect
        if (lastConsumedToken.longValue == token) return@LaunchedEffect

        lastConsumedToken.longValue = token
        alphaAnim.snapTo(0f)
        alphaAnim.animateTo(1f, tween(150))
        delay(500)
        alphaAnim.animateTo(0f, tween(1500))
    }

    return alphaAnim.value
}

@Composable
fun Modifier.accentHighlight(
    alpha: Float,
    glowColor: Color? = null,
    glowRadius: Dp = LocalDimensions.current.messageCornerRadius
): Modifier {
    val accentColor = glowColor ?: LocalColors.current.accent

    return this.drawBehind {
        if (alpha > 0f) {
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    isAntiAlias = true
                    color = accentColor.copy(alpha = alpha).toArgb()
                    maskFilter = BlurMaskFilter(glowRadius.toPx(), BlurMaskFilter.Blur.OUTER)
                }

                canvas.nativeCanvas.drawRoundRect(
                    0f, 0f, size.width, size.height,
                    glowRadius.toPx(), glowRadius.toPx(),
                    paint
                )
            }
        }
    }
}

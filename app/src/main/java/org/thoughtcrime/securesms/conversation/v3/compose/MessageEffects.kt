package org.thoughtcrime.securesms.conversation.v3.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.thoughtcrime.securesms.ui.theme.LocalColors

@Composable
fun Modifier.highlight(
    highlightKey: Any?,
    flashColor: Color = LocalColors.current.backgroundSecondary
): Modifier {
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(highlightKey) {
        if (highlightKey != null) {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 500, easing = FastOutLinearInEasing)
            )
            delay(500)
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 2000, easing = LinearOutSlowInEasing)
            )
        }
    }

    return this.drawBehind {
        if (alpha.value > 0f) {
            drawRect(
                color = flashColor.copy(alpha = flashColor.alpha * alpha.value),
                size = size
            )
        }
    }
}
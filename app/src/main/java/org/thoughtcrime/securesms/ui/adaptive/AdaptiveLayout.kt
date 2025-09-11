package org.thoughtcrime.securesms.ui.adaptive

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

object AdaptiveBreakpoints {
    const val TWO_PANE_LANDSCAPE_MIN_WIDTH_DP = 480
    const val TWO_PANE_PORTRAIT_MIN_WIDTH_DP = 840
}

fun shouldUseTwoPane(widthDp: Int, isLandscape: Boolean): Boolean {
    return (isLandscape && widthDp >= AdaptiveBreakpoints.TWO_PANE_LANDSCAPE_MIN_WIDTH_DP) ||
            (widthDp >= AdaptiveBreakpoints.TWO_PANE_PORTRAIT_MIN_WIDTH_DP)
}

/**
 * Convenience helper that returns only the two-pane decision.
 * Equivalent to `rememberAdaptiveInfo().isTwoPane`, but cheaper when you only need the boolean.
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun rememberTwoPane(): Boolean {
    val configuration = LocalConfiguration.current
    return remember(configuration.orientation, configuration.screenWidthDp) {
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        shouldUseTwoPane(configuration.screenWidthDp, isLandscape)
    }
}

/**
 * Immutable, @Stable container for adaptive layout info.
 * Safe to hoist and pass to child composables without causing unnecessary recompositions.
 */
@Stable
data class AdaptiveInfo(
    val widthDp: Int,
    val heightDp: Int,
    val isLandscape: Boolean,
    val isTwoPane: Boolean
)

/**
 * Returns a stable snapshot of the window/adaptive state for the current composition.
 *
 * We can use this when multiple layout decisions depend on size/orientation (e.g., choose Row vs Column,
 * cap bubble width, scale a QR square).
 *
 * Prefer `rememberTwoPane()` if you only need the boolean.
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun rememberAdaptiveInfo(): AdaptiveInfo {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val twoPane = remember(configuration.orientation, configuration.screenWidthDp) {
        shouldUseTwoPane(configuration.screenWidthDp, landscape)
    }
    return AdaptiveInfo(configuration.screenWidthDp, configuration.screenHeightDp, landscape, twoPane)
}
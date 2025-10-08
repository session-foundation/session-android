package org.thoughtcrime.securesms.ui.adaptive

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Immutable, @Stable container for adaptive layout info.
 * Safe to hoist and pass to child composables without causing unnecessary recompositions.
 */
@Stable
data class AdaptiveInfo(
    val widthDp: Int,
    val heightDp: Int,
    val isLandscape: Boolean
)

/**
 * Returns a stable snapshot of the window/adaptive state for the current composition.
 * Currently we use this for landscape
 *
 * TODO: Create helpers for adaptive screen layouts
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun rememberAdaptiveInfo(): AdaptiveInfo {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return AdaptiveInfo(configuration.screenWidthDp, configuration.screenHeightDp, landscape)
}

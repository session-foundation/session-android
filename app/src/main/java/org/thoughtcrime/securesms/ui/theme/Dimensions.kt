package org.thoughtcrime.securesms.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalDimensions = staticCompositionLocalOf { Dimensions() }

data class Dimensions(
    val xxxsSpacing: Dp = 4.dp,
    val xxsSpacing: Dp = 8.dp,
    val xsSpacing: Dp = 12.dp,
    val smallSpacing: Dp = 16.dp,
    val spacing: Dp = 24.dp,
    val mediumSpacing: Dp = 36.dp,
    val xlargeSpacing: Dp = 64.dp,

    val appBarHeight: Dp = 64.dp,
    val minItemButtonHeight: Dp = 50.dp,
    val minLargeItemButtonHeight: Dp = 60.dp,
    val minButtonWidth: Dp = 160.dp,

    val indicatorHeight: Dp = 4.dp,

    val borderStroke: Dp = 1.dp,

    val badgeSize: Dp = 20.dp,
    val iconXSmall: Dp = 14.dp,
    val iconMedium: Dp = 24.dp,
    val iconLarge: Dp = 46.dp,
    val iconXLarge: Dp = 60.dp,
    val iconXXLarge: Dp = 80.dp,

    val shapeExtraSmall: Dp = 8.dp,
    val shapeSmall: Dp = 12.dp,
    val shapeMedium: Dp = 16.dp,
)

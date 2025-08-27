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
    val contentSpacing: Dp = 20.dp,
    val spacing: Dp = 24.dp,
    val mediumSpacing: Dp = 36.dp,
    val xlargeSpacing: Dp = 64.dp,

    val appBarHeight: Dp = 64.dp,
    val minSearchInputHeight: Dp = 35.dp,
    val minItemButtonHeight: Dp = 50.dp,
    val minLargeItemButtonHeight: Dp = 60.dp,
    val minButtonWidth: Dp = 160.dp,
    val minSmallButtonWidth: Dp = 50.dp,

    val indicatorHeight: Dp = 4.dp,

    val borderStroke: Dp = 1.dp,

    val iconXXSmall: Dp = 9.dp,
    val iconXSmall: Dp = 14.dp,
    val iconSmall: Dp = 20.dp,
    val iconMedium: Dp = 24.dp,
    val iconMediumAvatar: Dp = 26.dp,
    val iconLargeAvatar: Dp = 36.dp,
    val iconLarge: Dp = 46.dp,
    val iconXLarge: Dp = 60.dp,
    val iconXLargeAvatar: Dp = 128.dp,
    val iconXXLarge: Dp = 90.dp,
    val iconXXLargeAvatar: Dp = 190.dp,

    val shapeExtraSmall: Dp = 8.dp,
    val shapeSmall: Dp = 12.dp,
    val shapeMedium: Dp = 16.dp,

    val maxContentWidth: Dp = 410.dp,
    val maxTooltipWidth: Dp = 280.dp,
)

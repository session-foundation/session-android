package org.thoughtcrime.securesms.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter

interface ThemeColors {
    // properties to override for each theme
    val isLight: Boolean
    val accent: Color
    val onInvertedBackgroundAccent: Color
    val textAlert: Color
    val danger: Color
    val warning: Color
    val disabled: Color
    val background: Color
    val backgroundSecondary: Color
    val backgroundTertiary: Color
    val text: Color
    val textSecondary: Color
    val borders: Color
    val textBubbleSent: Color
    val backgroundBubbleReceived: Color
    val textBubbleReceived: Color
    val qrCodeContent: Color
    val qrCodeBackground: Color
    val accentButtonFillText: Color
    val accentText: Color
}

// extra functions and properties that work for all themes
val ThemeColors.textSelectionColors
    get() = TextSelectionColors(
        handleColor = accent,
        backgroundColor = accent.copy(alpha = 0.5f)
    )

fun ThemeColors.text(isError: Boolean): Color = if (isError) danger else text
fun ThemeColors.textSecondary(isError: Boolean): Color = if (isError) danger else textSecondary
fun ThemeColors.borders(isError: Boolean): Color = if (isError) danger else borders

fun ThemeColors.toMaterialColors() = if (isLight) {
    lightColorScheme(
        primary = background,
        secondary = backgroundSecondary,
        tertiary = backgroundSecondary,
        onPrimary = text,
        onSecondary = text,
        onTertiary = text,
        background = background,
        surface = background,
        surfaceVariant = background,
        onBackground = text,
        onSurface = text,
        scrim = blackAlpha40,
        outline = text,
        outlineVariant = text
    )
} else {
    darkColorScheme(
        primary = background,
        secondary = backgroundSecondary,
        tertiary = backgroundSecondary,
        onPrimary = text,
        onSecondary = text,
        onTertiary = text,
        background = background,
        surface = background,
        surfaceVariant = background,
        onBackground = text,
        onSurface = text,
        scrim = blackAlpha40,
        outline = text,
        outlineVariant = text
    )
}


@Composable
fun transparentButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = LocalColors.current.disabled
)

@Composable
fun dangerButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.Transparent,
    contentColor = LocalColors.current.danger,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = LocalColors.current.disabled
)

@Composable
fun accentTextButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.Transparent,
    contentColor = LocalColors.current.accentText,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = LocalColors.current.disabled
)

// Our themes
data class ClassicDark(override val accent: Color = primaryGreen) : ThemeColors {
    override val isLight = false
    override val danger = dangerDark
    override val warning = primaryOrange
    override val disabled = disabledDark
    override val background = classicDark0
    override val backgroundSecondary = classicDark1
    override val backgroundTertiary = classicDark2
    override val onInvertedBackgroundAccent = background
    override val text = classicDark6
    override val textSecondary = classicDark5
    override val borders = classicDark3
    override val textBubbleSent = Color.Black
    override val backgroundBubbleReceived = classicDark2
    override val textBubbleReceived = Color.White
    override val qrCodeContent = background
    override val qrCodeBackground = text
    override val accentButtonFillText = Color.Black
    override val accentText = accent
    override val textAlert: Color = classicDark0
}

data class ClassicLight(override val accent: Color = primaryGreen) : ThemeColors {
    override val isLight = true
    override val danger = dangerLight
    override val warning = rust
    override val disabled = disabledLight
    override val background = classicLight6
    override val backgroundSecondary = classicLight5
    override val backgroundTertiary = classicLight4
    override val onInvertedBackgroundAccent = accent
    override val text = classicLight0
    override val textSecondary = classicLight1
    override val borders = classicLight3
    override val textBubbleSent = text
    override val backgroundBubbleReceived = classicLight4
    override val textBubbleReceived = classicLight4
    override val qrCodeContent = text
    override val qrCodeBackground = backgroundSecondary
    override val accentButtonFillText = Color.Black
    override val accentText = text
    override val textAlert: Color = classicLight0
}

data class OceanDark(override val accent: Color = primaryBlue) : ThemeColors {
    override val isLight = false
    override val danger = dangerDark
    override val warning = primaryOrange
    override val disabled = disabledDark
    override val background = oceanDark2
    override val backgroundSecondary = oceanDark1
    override val backgroundTertiary = oceanDark0
    override val onInvertedBackgroundAccent = background
    override val text = oceanDark7
    override val textSecondary = oceanDark5
    override val borders = oceanDark4
    override val textBubbleSent = Color.Black
    override val backgroundBubbleReceived = oceanDark4
    override val textBubbleReceived = oceanDark4
    override val qrCodeContent = background
    override val qrCodeBackground = text
    override val accentButtonFillText = Color.Black
    override val accentText = accent
    override val textAlert: Color = oceanDark0
}

data class OceanLight(override val accent: Color = primaryBlue) : ThemeColors {
    override val isLight = true
    override val danger = dangerLight
    override val warning = rust
    override val disabled = disabledLight
    override val background = oceanLight7
    override val backgroundSecondary = oceanLight6
    override val backgroundTertiary = oceanLight5
    override val onInvertedBackgroundAccent = background
    override val text = oceanLight1
    override val textSecondary = oceanLight2
    override val borders = oceanLight3
    override val textBubbleSent = text
    override val backgroundBubbleReceived = oceanLight4
    override val textBubbleReceived = oceanLight1
    override val qrCodeContent = text
    override val qrCodeBackground = backgroundSecondary
    override val accentButtonFillText = Color.Black
    override val accentText = text
    override val textAlert: Color = oceanLight0
}

@Preview
@Composable
fun PreviewThemeColors(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) { ThemeColors() }
}

@Composable
private fun ThemeColors() {
    Column {
        Box(Modifier.background(LocalColors.current.accent)) {
            Text("accent", style = LocalType.current.base)
        }
        Box(Modifier.background(LocalColors.current.accentText)) {
            Text("accentText", style = LocalType.current.base)
        }
        Box(Modifier.background(LocalColors.current.background)) {
            Text("background", style = LocalType.current.base)
        }
        Box(Modifier.background(LocalColors.current.backgroundSecondary)) {
            Text("backgroundSecondary", style = LocalType.current.base)
        }
        Box(Modifier.background(LocalColors.current.backgroundTertiary)) {
            Text("backgroundTertiary", style = LocalType.current.base)
        }
        Box(Modifier.background(LocalColors.current.text)) {
            Text("text", style = LocalType.current.base)
        }
        Box(Modifier.background(LocalColors.current.textSecondary)) {
            Text("textSecondary", style = LocalType.current.base)
        }
        Box(Modifier.background(LocalColors.current.danger)) {
            Text("danger", style = LocalType.current.base)
        }
        Box(Modifier.background(LocalColors.current.borders)) {
            Text("border", style = LocalType.current.base)
        }
        Box(Modifier.background(LocalColors.current.warning)) {
            Text("alertOnWarning", style = LocalType.current.base, color = LocalColors.current.textAlert)
        }
    }
}
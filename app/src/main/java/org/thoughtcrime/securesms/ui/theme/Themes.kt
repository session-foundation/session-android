package org.thoughtcrime.securesms.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import org.session.libsession.utilities.AppTextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.ApplicationContext

// Globally accessible composition local objects
val LocalColors = compositionLocalOf <ThemeColors> { ClassicDark() }
val LocalType = compositionLocalOf { sessionTypography }

var cachedColorsProvider: ThemeColorsProvider? = null

fun invalidateComposeThemeColors() {
    // invalidate compose theme colors
    cachedColorsProvider = null
}

/**
 * Apply a Material2 compose theme based on user selections in SharedPreferences.
 */
@Composable
fun SessionMaterialTheme(
    preferences: TextSecurePreferences =
        (LocalContext.current.applicationContext as ApplicationContext).textSecurePreferences.get(),
    content: @Composable () -> Unit
) {
    val cachedColors = cachedColorsProvider ?: preferences.getColorsProvider().also { cachedColorsProvider = it }

    SessionMaterialTheme(
        colors = cachedColors.get(),
        content = content
    )
}

/**
 * Apply a given [ThemeColors], and our typography and shapes as a Material 3 Compose Theme.
 **/
@Composable
fun SessionMaterialTheme(
    colors: ThemeColors,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = colors.toMaterialColors(),
        typography = sessionTypography.asMaterialTypography(),
        shapes = sessionShapes(),
    ) {
        CompositionLocalProvider(
            LocalColors provides colors,
            LocalType provides sessionTypography,
            LocalContentColor provides colors.text,
            LocalTextSelectionColors provides colors.textSelectionColors,
            content = content
        )
    }
}

val pillShape = RoundedCornerShape(percent = 50)
val buttonShape = pillShape

@Composable
fun sessionShapes() = Shapes(
    extraSmall = RoundedCornerShape(LocalDimensions.current.shapeExtraSmall),
    small = RoundedCornerShape(LocalDimensions.current.shapeSmall),
    medium = RoundedCornerShape(LocalDimensions.current.shapeMedium)

)

/**
 * Set the Material 2 theme and a background for Compose Previews.
 */
@Composable
fun PreviewTheme(
    colors: ThemeColors = LocalColors.current,
    content: @Composable () -> Unit
) {
    SessionMaterialTheme(colors) {
        Box(modifier = Modifier.background(color = LocalColors.current.background)) {
            content()
        }
    }
}

// used for previews
class SessionColorsParameterProvider : PreviewParameterProvider<ThemeColors> {
    override val values = sequenceOf(ClassicDark(), ClassicLight(), OceanDark(), OceanLight())
}

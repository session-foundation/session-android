package org.thoughtcrime.securesms.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.LaunchedEffectAsync
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.ui.theme.buttonShape
import org.thoughtcrime.securesms.ui.theme.sessionShapes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Base [Button] implementation
 */
@Composable
fun Button(
    onClick: () -> Unit,
    type: ButtonType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonStyle.Large,
    shape: Shape = buttonShape,
    minWidth: Dp = LocalDimensions.current.minButtonWidth,
    border: BorderStroke? = type.border(enabled),
    colors: ButtonColors = type.buttonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentPadding: PaddingValues = type.contentPadding,
    content: @Composable RowScope.() -> Unit
) {
    style.applyButtonConstraints {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = style.minHeight)
                .defaultMinSize(minWidth = minWidth),
            enabled = enabled,
            interactionSource = interactionSource,
            elevation = null,
            shape = shape,
            border = border,
            colors = colors,
            contentPadding = contentPadding
        ) {
            // Button sets LocalTextStyle, so text style is applied inside to override that.
            style.applyTextConstraints {
                content()
            }
        }
    }
}

/**
 * Courtesy [Button] implementation for buttons that just display text.
 */
@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    type: ButtonType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonStyle.Large,
    shape: Shape = buttonShape,
    minWidth: Dp = LocalDimensions.current.minButtonWidth,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    Button(onClick, type, modifier, enabled, style, shape, minWidth = minWidth, interactionSource = interactionSource) {
        Text(text)
    }
}

@Composable fun FillButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.Fill, modifier, enabled)
}

@Composable fun AccentFillButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.AccentFill, modifier, enabled)
}

@Composable fun FillButtonRect(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.Fill, modifier, enabled, shape = sessionShapes().extraSmall)
}

@Composable fun TertiaryFillButtonRect(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.TertiaryFill, modifier, enabled, shape = sessionShapes().extraSmall)
}

@Composable fun AccentFillButtonRect(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.AccentFill, modifier, enabled, shape = sessionShapes().extraSmall)

}

@Composable fun AccentFillButtonRect(modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, ButtonType.AccentFill, modifier = modifier, enabled = enabled, shape = sessionShapes().extraSmall, content = content)
}

@Composable fun OutlineButton(text: String, modifier: Modifier = Modifier, color: Color = LocalColors.current.text, enabled: Boolean = true, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.Outline(color), modifier, enabled)
}

@Composable fun AccentOutlineButtonRect(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.Outline(LocalColors.current.accentText), modifier, enabled, shape = sessionShapes().extraSmall)
}

@Composable fun OutlineButton(modifier: Modifier = Modifier, color: Color = LocalColors.current.text, enabled: Boolean = true, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Button(
        onClick = onClick,
        type = ButtonType.Outline(color),
        modifier = modifier,
        enabled = enabled,
        content = content
    )
}

@Composable fun AccentOutlineButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                                    minWidth: Dp = LocalDimensions.current.minButtonWidth, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.Outline(LocalColors.current.accentText), modifier, enabled, minWidth = minWidth)
}

@Composable fun AccentOutlineButton(modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Button(onClick, ButtonType.Outline(LocalColors.current.accentText), modifier, enabled, content = content)
}

@Composable fun SlimOutlineButton(modifier: Modifier = Modifier, color: Color = LocalColors.current.text, enabled: Boolean = true, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Button(onClick, ButtonType.Outline(color), modifier, enabled, ButtonStyle.Slim, content = content)
}

/**
 * Courtesy [SlimOutlineButton] implementation for buttons that just display text.
 */
@Composable fun SlimOutlineButton(text: String, modifier: Modifier = Modifier, color: Color = LocalColors.current.text, enabled: Boolean = true, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.Outline(color), modifier, enabled, ButtonStyle.Slim)
}

@Composable fun SlimAccentOutlineButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(text, onClick, ButtonType.Outline(LocalColors.current.accentText), modifier, enabled, ButtonStyle.Slim)
}

@Composable
fun AcccentOutlineCopyButton(
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Large,
    onClick: () -> Unit
) {
    OutlineCopyButton(modifier, style, LocalColors.current.accentText, onClick)
}

@Composable
fun SlimOutlineCopyButton(
    modifier: Modifier = Modifier,
    color: Color = LocalColors.current.text,
    onClick: () -> Unit
) {
    OutlineCopyButton(modifier, ButtonStyle.Slim, color, onClick)
}

@Composable
fun OutlineCopyButton(
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Large,
    color: Color = LocalColors.current.text,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Button(
        modifier = modifier.qaTag(R.string.AccessibilityId_copy),
        interactionSource = interactionSource,
        style = style,
        type = ButtonType.Outline(color),
        onClick = onClick
    ) {
        CopyButtonContent(interactionSource)
    }
}

@Composable
fun CopyButtonContent(interactionSource: MutableInteractionSource) {
    TemporaryClickedContent(
        interactionSource = interactionSource,
        content = { Text(stringResource(R.string.copy)) },
        temporaryContent = { Text(stringResource(R.string.copied)) }
    )
}

@Composable
fun TemporaryClickedContent(
    interactionSource: MutableInteractionSource,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
    temporaryContent: @Composable AnimatedVisibilityScope.() -> Unit,
    temporaryDelay: Duration = 2.seconds
) {
    var clicked by remember { mutableStateOf(false) }

    LaunchedEffectAsync {
        interactionSource.releases.collectLatest {
            clicked = true
            delay(temporaryDelay)
            clicked = false
        }
    }

    // Using a Box because the Buttons add children in a Row
    // and they will jank as they are added and removed.
    Box(contentAlignment = Alignment.Center) {
        AnimatedVisibility(!clicked, enter = fadeIn(), exit = fadeOut(), content = content)
        AnimatedVisibility(clicked, enter = fadeIn(), exit = fadeOut(), content = temporaryContent)
    }
}

/**
 * Base [BorderlessButton] implementation.
 */
@Composable
fun BorderlessButton(
    modifier: Modifier = Modifier,
    color: Color = LocalColors.current.text,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        style = ButtonStyle.Borderless,
        type = ButtonType.Borderless(color),
        content = content
    )
}

/**
 * Courtesy [BorderlessButton] implementation that accepts [text] as a [String].
 */
@Composable
fun BorderlessButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalColors.current.text,
    onClick: () -> Unit
) {
    BorderlessButton(modifier, color, onClick) { Text(text) }
}

@Composable
fun BorderlessButtonWithIcon(
    text: String,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalType.current.base.bold(),
    color: Color = LocalColors.current.text,
    onClick: () -> Unit
) {
    BorderlessButton(
        modifier = modifier,
        color = color,
        onClick = onClick
    ) {
        AnnotatedTextWithIcon(
            text = text,
            iconRes = iconRes,
            color = color,
            style = style
        )
    }
}

@Composable
fun BorderlessHtmlButton(
    textId: Int,
    modifier: Modifier = Modifier,
    color: Color = LocalColors.current.text,
    onClick: () -> Unit
) {
    BorderlessButton(modifier, color, onClick) {
        Text(
            text = annotatedStringResource(textId),
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

val MutableInteractionSource.releases
    get() = interactions.filter { it is PressInteraction.Release }

@OptIn(ExperimentalLayoutApi::class)
@Preview
@Composable
private fun VariousButtons(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        FlowRow(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2
        ) {
            AccentFillButton("Accent Fill") {}
            AccentFillButton("Accent Fill Disabled", enabled = false) {}
            FillButton("Fill Button") {}
            FillButton("Fill Button Disabled", enabled = false) {}
            AccentOutlineButton("Accent Outline Button") {}
            AccentOutlineButton("Accent Outline Disabled", enabled = false) {}
            OutlineButton("Outline Button") {}
            OutlineButton("Outline Button Disabled", enabled = false) {}
            SlimOutlineButton("Slim Outline") {}
            SlimOutlineButton("Slim Outline Disabled", enabled = false) {}
            SlimAccentOutlineButton("Slim Accent") {}
            SlimOutlineButton("Slim Danger", color = LocalColors.current.danger) {}
            BorderlessButton("Borderless Button") {}
            BorderlessButton("Borderless Secondary", color = LocalColors.current.textSecondary) {}
            FillButtonRect("Fill Rect") {}
            FillButtonRect("Fill Rect Disabled", enabled = false) {}
            TertiaryFillButtonRect("Tertiary Fill Rect") {}
            AccentFillButtonRect("Accent Fill Rect") {}
            AccentFillButtonRect("Accent Fill Rect Disabled", enabled = false) {}
            AccentOutlineButtonRect("Outline Button Rect") {}
            AccentOutlineButtonRect("Outline ButtonDisabled", enabled = false) {}
        }
    }
}
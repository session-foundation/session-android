package org.thoughtcrime.securesms.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.thoughtcrime.securesms.conversation.disappearingmessages.ExpiryType
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.transparentButtonColors
import kotlin.time.Duration.Companion.days

@Composable
fun RadioButton(
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    @DrawableRes iconRes: Int? = null,
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable RowScope.() -> Unit = {}
) {
    TextButton(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = true,
                role = Role.RadioButton,
                onClick = onClick
            ),
        enabled = enabled,
        colors = transparentButtonColors(),
        onClick = onClick,
        shape = RectangleShape,
        contentPadding = contentPadding
    ) {
        if(iconRes != null){
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier
                    .size(LocalDimensions.current.iconMedium),
            )

            Spacer(modifier = Modifier.width(LocalDimensions.current.spacing))
        }

        content()

        Spacer(modifier = Modifier.width(LocalDimensions.current.spacing))
        RadioButtonIndicator(
            selected = selected,
            enabled = enabled,
            modifier = Modifier
                .align(Alignment.CenterVertically)
        )
    }
}

@Composable
fun RadioButtonIndicator(
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = LocalDimensions.current.iconMedium
) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            selected,
            modifier = Modifier
                .size(size)
                .padding(2.5.dp)
                .clip(CircleShape),
            enter = scaleIn(),
            exit = scaleOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = if (enabled) LocalColors.current.accent else LocalColors.current.disabled,
                        shape = CircleShape
                    )
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .aspectRatio(1f)
                .border(
                    width = LocalDimensions.current.borderStroke,
                    color = if (enabled) LocalColors.current.text else LocalColors.current.disabled,
                    shape = CircleShape
                )
        ) {}
    }
}

/**
 * Convenience access for a TitledRadiobutton used in dialogs
 */
@Composable
fun <T> DialogTitledRadioButton(
    modifier: Modifier = Modifier,
    option: RadioOption<T>,
    onClick: () -> Unit
) {
    TitledRadioButton(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = LocalDimensions.current.xxsSpacing,
            vertical = 0.dp
        ),
        titleStyle = LocalType.current.large,
        option = option,
        onClick = onClick
    )
}

@Composable
fun <T> TitledRadioButton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = LocalDimensions.current.spacing,
        vertical = LocalDimensions.current.smallSpacing
    ),
    titleStyle: TextStyle = LocalType.current.h8,
    subtitleStyle: TextStyle = LocalType.current.extraSmall,
    option: RadioOption<T>,
    onClick: () -> Unit
) {
    RadioButton(
        modifier = modifier.qaTag(option.qaTag?.string()),
        onClick = onClick,
        selected = option.selected,
        enabled = option.enabled,
        iconRes = option.iconRes,
        contentPadding = contentPadding,
        content = {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically)
            ) {
                Text(
                    text = option.title(),
                    style = titleStyle
                )
                option.subtitle?.let {
                    Text(
                        text = it(),
                        style = subtitleStyle
                    )
                }
            }
        }
    )
}

@Preview
@Composable
fun PreviewTextRadioButton() {
    PreviewTheme {
        TitledRadioButton(
            option = RadioOption<ExpiryMode>(
                value = ExpiryType.AFTER_SEND.mode(7.days),
                title = GetString(7.days),
                subtitle = GetString("This is a subtitle"),
                enabled = true,
                selected = true
            )
        ) {}
    }
}

@Preview
@Composable
fun PreviewTextIconRadioButton() {
    PreviewTheme {
        TitledRadioButton(
            option = RadioOption<ExpiryMode>(
                value = ExpiryType.AFTER_SEND.mode(7.days),
                title = GetString(7.days),
                subtitle = GetString("This is a subtitle"),
                iconRes = R.drawable.ic_users_group_custom,
                enabled = true,
                selected = true
            )
        ) {}
    }
}

@Preview
@Composable
fun PreviewDisabledTextRadioButton() {
    PreviewTheme {
        TitledRadioButton(
            option = RadioOption<ExpiryMode>(
                value = ExpiryType.AFTER_SEND.mode(7.days),
                title = GetString(7.days),
                subtitle = GetString("This is a subtitle"),
                enabled = false,
                selected = true
            )
        ) {}
    }
}

@Preview
@Composable
fun PreviewDeselectedTextRadioButton() {
    PreviewTheme {
        TitledRadioButton(
            option = RadioOption<ExpiryMode>(
                value = ExpiryType.AFTER_SEND.mode(7.days),
                title = GetString(7.days),
                subtitle = GetString("This is a subtitle"),
                enabled = true,
                selected = false
            )
        ) {}
    }
}

package org.thoughtcrime.securesms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@Composable
fun SessionSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        modifier = modifier,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = LocalColors.current.background,
            checkedTrackColor = LocalColors.current.accent,
            uncheckedThumbColor = LocalColors.current.disabled,
            uncheckedTrackColor = LocalColors.current.disabled.copy(alpha = 0.3f),
            uncheckedBorderColor = Color.Transparent,
        )
    )
}

@Preview
@Composable
fun PreviewSwitch(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
){
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SessionSwitch(
                checked = true,
                onCheckedChange = {}
            )

            SessionSwitch(
                checked = false,
                onCheckedChange = {}
            )
        }
    }
}
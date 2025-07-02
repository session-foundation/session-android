package org.thoughtcrime.securesms.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.thoughtcrime.securesms.ui.theme.LocalColors

// todo Get proper styling that works well with ax on all themes and then move this composable in the components file
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
            checkedThumbColor = LocalColors.current.accent,
            checkedTrackColor = LocalColors.current.accent.copy(alpha = 0.3f),
            uncheckedThumbColor = LocalColors.current.disabled,
            uncheckedTrackColor = LocalColors.current.disabled.copy(alpha = 0.3f),
            uncheckedBorderColor = Color.Transparent,
        )
    )
}
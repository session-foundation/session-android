package org.thoughtcrime.securesms.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.thoughtcrime.securesms.ui.theme.LocalColors

// todo Get proper styling that works well with ax on all themes and then move this composable in the components file
@Composable
fun SessionSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Switch(
        checked = checked,
        modifier = modifier,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = LocalColors.current.primary,
            checkedTrackColor = LocalColors.current.background,
            uncheckedThumbColor = LocalColors.current.text,
            uncheckedTrackColor = LocalColors.current.background,
        )
    )
}
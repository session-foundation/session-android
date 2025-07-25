package org.thoughtcrime.securesms.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.SessionShieldIcon
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@Composable
internal fun SeedReminder(startRecoveryPasswordActivity: () -> Unit) {
    Column {
        // Color Strip
        Box(
            Modifier
                .fillMaxWidth()
                .height(LocalDimensions.current.indicatorHeight)
                .background(LocalColors.current.accent)
        )
        Row(
            Modifier
                .background(LocalColors.current.backgroundSecondary)
                .padding(
                    horizontal = LocalDimensions.current.spacing,
                    vertical = LocalDimensions.current.smallSpacing
                )
        ) {
            Column(Modifier.weight(1f)) {
                Row {
                    Text(
                        stringResource(R.string.recoveryPasswordBannerTitle),
                        style = LocalType.current.h8
                    )
                    Spacer(Modifier.requiredWidth(LocalDimensions.current.xsSpacing))
                    SessionShieldIcon(
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                Text(
                    stringResource(R.string.recoveryPasswordBannerDescription),
                    style = LocalType.current.small
                )
            }
            Spacer(Modifier.width(LocalDimensions.current.smallSpacing))
            AccentOutlineButton(
                text = stringResource(R.string.theContinue),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .qaTag(R.string.AccessibilityId_recoveryPasswordBanner),
                minWidth = 0.dp,
                onClick = startRecoveryPasswordActivity
            )
        }
    }
}

@Preview
@Composable
private fun PreviewSeedReminder(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        SeedReminder { }
    }
}

package org.thoughtcrime.securesms.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.SessionShieldIcon
import org.thoughtcrime.securesms.ui.components.PrimaryOutlineButton
import org.thoughtcrime.securesms.ui.contentDescription
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@Composable
internal fun SeedReminder(startRecoveryPasswordActivity: () -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Color Strip
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(LocalDimensions.current.indicatorHeight)
                    .background(LocalColors.current.primary)
            )

            Row(
                Modifier
                    .background(LocalColors.current.backgroundSecondary)
                    .padding(
                        top = LocalDimensions.current.smallSpacing,
                        bottom = LocalDimensions.current.smallSpacing,
                        start = LocalDimensions.current.spacing,
                        end = LocalDimensions.current.xxsSpacing
                    )
            ) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(
                            stringResource(R.string.recoveryPasswordBannerTitle),
                            style = LocalType.current.h8
                        )
                        Spacer(Modifier.requiredWidth(LocalDimensions.current.smallSpacing))
                        SessionShieldIcon()
                    }
                    Text(
                        stringResource(R.string.recoveryPasswordBannerDescription),
                        style = LocalType.current.small
                    )
                }
                Spacer(Modifier.width(LocalDimensions.current.smallSpacing))
                PrimaryOutlineButton(
                    text = stringResource(R.string.theContinue),
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .contentDescription(R.string.AccessibilityId_recoveryPasswordBanner),
                    onClick = startRecoveryPasswordActivity
                )
                Spacer(Modifier.width(LocalDimensions.current.xsSpacing))
            }

        } // End of top-level column

        // The "X" icon is placed as a sibling to the Column, positioned at the top-right corner.
        IconButton(
            onClick = { onDismiss() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                // Negative offsets to nudge the icon closer to the top-right corner
                .offset(
                    x = LocalDimensions.current.xsSpacing,
                    y = (-LocalDimensions.current.xxsSpacing)
                )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_dialog_x),
                tint = LocalColors.current.text,
                contentDescription = "Close"
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
        SeedReminder(startRecoveryPasswordActivity = {}, onDismiss = {})
    }
}
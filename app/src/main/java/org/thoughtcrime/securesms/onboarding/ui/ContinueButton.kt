package org.thoughtcrime.securesms.onboarding.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

@Composable
fun ContinueAccentOutlineButton(modifier: Modifier, onContinue: () -> Unit) {
    AccentOutlineButton(
        stringResource(R.string.theContinue),
        modifier = modifier
            .qaTag(R.string.AccessibilityId_theContinue)
            .fillMaxWidth()
            .padding(horizontal = LocalDimensions.current.xlargeSpacing)
            .padding(bottom = LocalDimensions.current.smallSpacing),
        onClick = onContinue,
    )
}

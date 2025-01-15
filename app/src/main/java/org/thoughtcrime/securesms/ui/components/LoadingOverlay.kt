package org.thoughtcrime.securesms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import org.thoughtcrime.securesms.ui.theme.LocalColors

/**
 * A max-sized semi-transparent overlay that:
 * 1. Blocks the user from interacting with the screen.
 * 2. Shows a loading indicator in the center.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Preview
fun LoadingOverlay(modifier: Modifier = Modifier) {
    Box(modifier = modifier
        .fillMaxSize()
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {}
        )
        .background(LocalColors.current.backgroundSecondary.copy(alpha = .8f))
        .semantics { invisibleToUser() },
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
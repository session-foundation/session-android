package org.thoughtcrime.securesms.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.contentDescription

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current
) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = modifier.size(40.dp)
            .contentDescription(stringResource(R.string.loading)),
        color = color
    )
}

@Composable
fun SmallCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current
) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = modifier.size(20.dp)
            .contentDescription(stringResource(R.string.loading)),
        color = color,
        strokeWidth = 2.dp
    )
}

@Composable
fun ExtraSmallCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current
) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = modifier.size(16.dp)
            .contentDescription(stringResource(R.string.loading)),
        color = color,
        strokeWidth = 2.dp
    )
}

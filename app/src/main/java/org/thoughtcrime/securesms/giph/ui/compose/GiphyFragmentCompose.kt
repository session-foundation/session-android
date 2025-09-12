package org.thoughtcrime.securesms.giph.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.loki.messenger.R

/** Called from Java to set Compose content for the loading view. */
fun setGiphyLoading(composeView: ComposeView) {
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    composeView.setContent { GiphyLoading() }
}

/** Called from Java to set Compose content for the empty/no-results view. */
fun setGiphyNoResults(composeView: ComposeView, @StringRes messageId: Int = R.string.searchMatchesNone) {
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    composeView.setContent { GiphyNoResults(messageId) }
}

@Composable
private fun GiphyLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) { CircularProgressIndicator() }
}

@Composable
private fun GiphyNoResults(@StringRes messageId: Int) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = stringResource(messageId), style = MaterialTheme.typography.bodyMedium)
    }
}
package org.thoughtcrime.securesms.giph.ui.compose

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

sealed class GiphyOverlayState {
    data object Hidden : GiphyOverlayState()
    data object Loading : GiphyOverlayState()
    data class Empty(val messageId: Int = R.string.searchMatchesNone) : GiphyOverlayState()
}

fun bindGiphyOverlay(composeView: ComposeView, stateFlow: StateFlow<GiphyOverlayState>) {
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    composeView.setContent {
        val state by stateFlow.collectAsState()
        GiphyOverlay(state)
    }
}

@Composable
private fun GiphyOverlay(state: GiphyOverlayState) {
    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = state, label = "giphyOverlay") { s ->
            when (s) {
                is GiphyOverlayState.Hidden -> {}
                is GiphyOverlayState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = LocalDimensions.current.spacing),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is GiphyOverlayState.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(LocalDimensions.current.spacing),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = stringResource(s.messageId),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
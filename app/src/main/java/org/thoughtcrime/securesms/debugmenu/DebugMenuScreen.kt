package org.thoughtcrime.securesms.debugmenu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

@Composable
fun DebugMenuScreen(
    modifier: Modifier = Modifier,
    viewModel: DebugMenuViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    DebugMenu(
        modifier = modifier,
        uiState = uiState,
        sendCommand = viewModel::onCommand,
        onClose = onBack
    )
}

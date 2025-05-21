package org.thoughtcrime.securesms.tokenpage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TokenPageScreen(
    modifier: Modifier = Modifier,
    tokenPageViewModel: TokenPageViewModel = viewModel(),
    onClose: () -> Unit
) {
    val uiState by tokenPageViewModel.uiState.collectAsState()

    // Remember callbacks to prevent recomposition when functions change
    val rememberedOnCommand = remember { tokenPageViewModel::onCommand }
    val rememberedOnClose = remember { onClose }

    TokenPage(
        modifier = modifier,
        uiState = uiState,
        sendCommand = rememberedOnCommand,
        onClose = rememberedOnClose
    )
}

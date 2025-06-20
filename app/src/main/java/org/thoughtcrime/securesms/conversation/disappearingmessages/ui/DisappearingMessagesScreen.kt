package org.thoughtcrime.securesms.conversation.disappearingmessages.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.thoughtcrime.securesms.conversation.disappearingmessages.DisappearingMessagesViewModel

@Composable
fun DisappearingMessagesScreen(
    viewModel: DisappearingMessagesViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState(UiState())
    DisappearingMessages(
        state = uiState,
        onOptionSelected = viewModel::onOptionSelected,
        onSetClicked = viewModel::onSetClicked,
        onBack = onBack
    )
}
package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.thoughtcrime.securesms.groups.InviteMembersViewModel
import org.thoughtcrime.securesms.home.startconversation.newmessage.Callbacks
import org.thoughtcrime.securesms.home.startconversation.newmessage.NewMessage
import org.thoughtcrime.securesms.home.startconversation.newmessage.State

@Composable
internal fun InviteAccountIdScreen(
    viewModel: InviteMembersViewModel,
    state: State, // new message state
    qrErrors: Flow<String> = emptyFlow(),
    callbacks: Callbacks = object : Callbacks {},
    onBack: () -> Unit = {},
    onHelp: () -> Unit = {},
    onSendInvite: (shareHistory: Boolean) -> Unit
) {
    InviteAccountId(
        state = state,
        inviteState = viewModel.uiState.collectAsState().value.inviteContactsDialog,
        qrErrors = qrErrors,
        callbacks = callbacks,
        onBack = onBack,
        onHelp = onHelp,
        onSendInvite = onSendInvite,
        onDismissInviteDialog = { viewModel.sendCommand(InviteMembersViewModel.Commands.DismissSendInviteDialog) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteAccountId(
    state: State,
    inviteState: InviteMembersViewModel.InviteContactsDialogState,
    qrErrors: Flow<String> = emptyFlow(),
    callbacks: Callbacks = object : Callbacks {},
    onBack: () -> Unit = {},
    onHelp: () -> Unit = {},
    onSendInvite: (Boolean) -> Unit,
    onDismissInviteDialog: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { paddings ->
        Box(
            modifier = Modifier.padding(
                top = paddings.calculateTopPadding(),
                bottom = paddings.calculateBottomPadding()
            )
        ) {
            NewMessage(
                state = state,
                qrErrors = qrErrors,
                callbacks = callbacks,
                onBack = { onBack() },
                onClose = { onBack() },
                onHelp = { onHelp() },
                isInvite = true,
            )
        }
    }

    if (inviteState.visible) {
        InviteMembersDialog(
            state = inviteState,
            onInviteClicked = onSendInvite,
            onDismiss = onDismissInviteDialog
        )
    }
}

@Preview
@Composable
fun PreviewInviteAccountId() {
    InviteAccountId(
        state = State(
            newMessageIdOrOns = "",
            isTextErrorColor = false,
            error = null,
            loading = false,
            showUrlDialog = false,
            helpUrl = "https://getsession.org/account-ids",
            validIdFromQr = "",
        ),
        onBack = { },
        onHelp = { },
        onSendInvite = {_ -> },
        inviteState = InviteMembersViewModel.InviteContactsDialogState(),
        qrErrors = emptyFlow(),
        onDismissInviteDialog = {},
    )
}
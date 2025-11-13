package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import network.loki.messenger.R
import org.thoughtcrime.securesms.home.startconversation.newmessage.Callbacks
import org.thoughtcrime.securesms.home.startconversation.newmessage.NewMessage
import org.thoughtcrime.securesms.home.startconversation.newmessage.State
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.components.DialogTitledRadioButton
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors

@Composable
internal fun InviteAccountIdScreen(
    state: State,
    qrErrors: Flow<String> = emptyFlow(),
    callbacks: Callbacks = object : Callbacks {},
    onBack: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    InviteAccountId(
        state = state,
        qrErrors = qrErrors,
        callbacks = callbacks,
        onBack = onBack,
        onHelp = onHelp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteAccountId(
    state: State,
    qrErrors: Flow<String> = emptyFlow(),
    callbacks: Callbacks = object : Callbacks {},
    onBack: () -> Unit = {},
    onHelp: () -> Unit = {},
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
                isInvite = true
            )
        }
    }

    if (state.showInviteDialog) {
        ShowInviteContactsDialog(
            onDoneClicked = {},
            onDismissDialog = {callbacks.onDismissInviteDialog()},
            onToggleShareHistory = callbacks::onToggleShareHistory
        )
    }
}

@Composable
fun ShowInviteContactsDialog(
    modifier: Modifier = Modifier,
    shareHistory: Boolean = false,
    onDoneClicked: (shareHistory: Boolean) -> Unit,
    onDismissDialog: () -> Unit,
    onToggleShareHistory : (Boolean) -> Unit
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            // hide dialog
            onDismissDialog()
        },
        title = annotatedStringResource(R.string.membersInviteTitle),
        text = annotatedStringResource(R.string.membersInviteShareDescription), // TODO: String from crowdin
        content = {
            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(LocalResources.current.getString(R.string.membersInviteShareMessageHistoryDays)),
                    selected = !shareHistory
                )
            ) {
               onToggleShareHistory(false)
            }

            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(LocalResources.current.getString(R.string.membersInviteShareNewMessagesOnly)),
                    selected = shareHistory,
                )
            ) {
                onToggleShareHistory(true)
            }
        },
        buttons = listOf(
            DialogButtonData(
                text = GetString(
                    LocalResources.current.getQuantityString(
                        R.plurals.membersInviteSend,
                        1,
                        1
                    )
                ),
                color = LocalColors.current.danger,
                dismissOnClick = false,
                onClick = {
                    onDismissDialog()
                    onDoneClicked(shareHistory)
                }
            ),
            DialogButtonData(
                text = GetString(stringResource(R.string.cancel)),
                onClick = {
                    onDismissDialog()
                }
            )
        )
    )
}

//@Preview
//@Composable
//fun PreviewInviteAccountId() {
//    InviteAccountIdScreen()
//}
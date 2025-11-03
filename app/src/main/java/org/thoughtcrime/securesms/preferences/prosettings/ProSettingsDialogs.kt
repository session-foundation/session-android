package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import network.loki.messenger.R
import org.thoughtcrime.securesms.openUrl
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.HideSimpleDialog
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.HideTCPolicyDialog
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.IconActionRowItem
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
import org.thoughtcrime.securesms.ui.TCPolicyDialog
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold

@Composable
fun ProSettingsDialogs(
    dialogsState: ProSettingsViewModel.DialogsState,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit
){
    // open link confirmation
    if(!dialogsState.openLinkDialogUrl.isNullOrEmpty()){
        OpenURLAlertDialog(
            url = dialogsState.openLinkDialogUrl,
            onDismissRequest = {
                // hide dialog
                sendCommand(ShowOpenUrlDialog(null))
            }
        )
    }

    // T&C + Policy dialog
    if(dialogsState.showTCPolicyDialog){
        TCPolicyDialog(
            tcsUrl = "https://getsession.org/pro/terms",
            privacyUrl = "https://getsession.org/pro/privacy",
            onDismissRequest = { sendCommand(HideTCPolicyDialog) },
        )
    }

    //  Simple dialogs
    if (dialogsState.showSimpleDialog != null) {
        val buttons = mutableListOf<DialogButtonData>()
        if(dialogsState.showSimpleDialog.positiveText != null) {
            buttons.add(
                DialogButtonData(
                    text = GetString(dialogsState.showSimpleDialog.positiveText),
                    color = if (dialogsState.showSimpleDialog.positiveStyleDanger) LocalColors.current.danger
                    else LocalColors.current.text,
                    qaTag = dialogsState.showSimpleDialog.positiveQaTag,
                    onClick = dialogsState.showSimpleDialog.onPositive
                )
            )
        }
        if(dialogsState.showSimpleDialog.negativeText != null){
            buttons.add(
                DialogButtonData(
                    text = GetString(dialogsState.showSimpleDialog.negativeText),
                    qaTag = dialogsState.showSimpleDialog.negativeQaTag,
                    onClick = dialogsState.showSimpleDialog.onNegative
                )
            )
        }

        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideSimpleDialog)
            },
            title = annotatedStringResource(dialogsState.showSimpleDialog.title),
            text = annotatedStringResource(dialogsState.showSimpleDialog.message),
            showCloseButton = dialogsState.showSimpleDialog.showXIcon,
            buttons = buttons
        )
    }
}
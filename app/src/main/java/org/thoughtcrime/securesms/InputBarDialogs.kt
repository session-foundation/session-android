package org.thoughtcrime.securesms


import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.CTAFeature
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.LongMessageProCTA
import org.thoughtcrime.securesms.ui.SimpleSessionProCTA
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.SessionMaterialTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputBarDialogs(
    inputBarDialogsState: InputbarViewModel.InputBarDialogsState,
    sendCommand: (InputbarViewModel.Commands) -> Unit
){
    SessionMaterialTheme {
        //  Simple dialogs
        if (inputBarDialogsState.showSimpleDialog != null) {
            val buttons = mutableListOf<DialogButtonData>()
            if(inputBarDialogsState.showSimpleDialog.positiveText != null) {
                buttons.add(
                    DialogButtonData(
                        text = GetString(inputBarDialogsState.showSimpleDialog.positiveText),
                        color = if (inputBarDialogsState.showSimpleDialog.positiveStyleDanger) LocalColors.current.danger
                        else LocalColors.current.text,
                        qaTag = inputBarDialogsState.showSimpleDialog.positiveQaTag,
                        onClick = inputBarDialogsState.showSimpleDialog.onPositive
                    )
                )
            }
            if(inputBarDialogsState.showSimpleDialog.negativeText != null){
                buttons.add(
                    DialogButtonData(
                        text = GetString(inputBarDialogsState.showSimpleDialog.negativeText),
                        qaTag = inputBarDialogsState.showSimpleDialog.negativeQaTag,
                        onClick = inputBarDialogsState.showSimpleDialog.onNegative
                    )
                )
            }

            AlertDialog(
                onDismissRequest = {
                    // hide dialog
                    sendCommand(InputbarViewModel.Commands.HideSimpleDialog)
                },
                title = annotatedStringResource(inputBarDialogsState.showSimpleDialog.title),
                text = annotatedStringResource(inputBarDialogsState.showSimpleDialog.message),
                showCloseButton = inputBarDialogsState.showSimpleDialog.showXIcon,
                buttons = buttons
            )
        }

        // Pro CTA
        if (inputBarDialogsState.sessionProCharLimitCTA) {
            LongMessageProCTA(
                onDismissRequest = {sendCommand(InputbarViewModel.Commands.HideSessionProCTA)}
            )
        }
    }
}

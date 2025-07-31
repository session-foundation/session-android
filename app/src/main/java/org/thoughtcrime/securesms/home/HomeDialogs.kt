package org.thoughtcrime.securesms.home

import androidx.compose.runtime.Composable
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HandleUserProfileCommand
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HidePinCTADialog
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HideUserProfileModal
import org.thoughtcrime.securesms.ui.PinProCTA
import org.thoughtcrime.securesms.ui.UserProfileModal
import org.thoughtcrime.securesms.ui.theme.SessionMaterialTheme

@Composable
fun HomeDialogs(
    dialogsState: HomeViewModel.DialogsState,
    sendCommand: (HomeViewModel.Commands) -> Unit
) {
    SessionMaterialTheme {
        // pin CTA
        if(dialogsState.pinCTA != null){
            PinProCTA(
                overTheLimit = dialogsState.pinCTA.overTheLimit,
                onDismissRequest = {
                    sendCommand(HidePinCTADialog)
                }
            )
        }

        if(dialogsState.userProfileModal != null){
            UserProfileModal(
                data = dialogsState.userProfileModal,
                onDismissRequest = {
                    sendCommand(HideUserProfileModal)
                },
                sendCommand = {
                    sendCommand(HandleUserProfileCommand(it))
                },
            )
        }
    }
}

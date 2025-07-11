package org.thoughtcrime.securesms.home

import androidx.compose.runtime.Composable
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.*
import org.thoughtcrime.securesms.ui.PinProCTA
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
                onUpgrade = {
                    sendCommand(GoToProUpgradeScreen)
                },

                onCancel = {
                    sendCommand(HidePinCTADialog)
                }
            )
        }
    }
}

package org.thoughtcrime.securesms.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.NonTranslatableStringConstants.PRO
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HandleUserProfileCommand
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HidePinCTADialog
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HideUserProfileModal
import org.thoughtcrime.securesms.home.startconversation.StartConversationDestination
import org.thoughtcrime.securesms.home.startconversation.StartConversationSheet
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination
import org.thoughtcrime.securesms.ui.AnimatedSessionProCTA
import org.thoughtcrime.securesms.ui.CTAFeature
import org.thoughtcrime.securesms.ui.PinProCTA
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.UserProfileModal
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
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

        if(dialogsState.showStartConversationSheet != null){
            StartConversationSheet(
                accountId = dialogsState.showStartConversationSheet.accountId,
                onDismissRequest = {
                    sendCommand(HomeViewModel.Commands.HideStartConversationSheet)
                }
            )
        }

        if(dialogsState.proExpiringCTA != null){
            val context = LocalContext.current
            AnimatedSessionProCTA(
                heroImageBg = R.drawable.cta_hero_generic_bg,
                heroImageAnimatedFg = R.drawable.cta_hero_generic_fg,
                title = stringResource(R.string.proExpiringSoon),
                badgeAtStart = true,
                text = Phrase.from(context,R.string.proExpiringSoonDescription)
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .put(TIME_KEY, dialogsState.proExpiringCTA.expiry)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format()
                    .toString(),
                features = listOf(
                    CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
                    CTAFeature.Icon(stringResource(R.string.proFeatureListLongerMessages)),
                    CTAFeature.Icon(stringResource(R.string.proFeatureListPinnedConversations)),
                ),
                positiveButtonText = stringResource(R.string.update),
                negativeButtonText = stringResource(R.string.close),
                onUpgrade = {
                    sendCommand(HomeViewModel.Commands.HideExpiringCTADialog)
                    sendCommand(HomeViewModel.Commands.GotoProSettings(ProSettingsDestination.UpdatePlan))
                },
                onCancel = {
                    sendCommand(HomeViewModel.Commands.HideExpiringCTADialog)
                }
            )
        }

        if(dialogsState.proExpiredCTA){
            val context = LocalContext.current
            AnimatedSessionProCTA(
                heroImageBg = R.drawable.cta_hero_generic_bg,
                heroImageAnimatedFg = R.drawable.cta_hero_generic_fg,
                title = stringResource(R.string.proExpired),
                badgeAtStart = true,
                disabled = true,
                text = Phrase.from(context,R.string.proExpiredDescription)
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format()
                    .toString(),
                features = listOf(
                    CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
                    CTAFeature.Icon(stringResource(R.string.proFeatureListLongerMessages)),
                    CTAFeature.Icon(stringResource(R.string.proFeatureListPinnedConversations)),
                ),
                positiveButtonText = stringResource(R.string.renew),
                negativeButtonText = stringResource(R.string.cancel),
                onUpgrade = {
                    sendCommand(HomeViewModel.Commands.HideExpiredCTADialog)
                    sendCommand(HomeViewModel.Commands.GotoProSettings(ProSettingsDestination.GetOrRenewPlan))
                },
                onCancel = {
                    sendCommand(HomeViewModel.Commands.HideExpiredCTADialog)
                }
            )
        }
    }
}


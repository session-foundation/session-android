package org.thoughtcrime.securesms.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.squareup.phrase.Phrase
import kotlinx.coroutines.delay
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.*
import org.thoughtcrime.securesms.home.startconversation.StartConversationSheet
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination
import org.thoughtcrime.securesms.ui.AnimatedSessionProCTA
import org.thoughtcrime.securesms.ui.CTAFeature
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
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
                proSubscription = dialogsState.pinCTA.proSubscription,
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

        // we need a delay before displaying this.
        // Setting the delay in the VM does not account for render and it seems to appear immediately
        var showExpiring by remember { mutableStateOf(false) }
        LaunchedEffect(dialogsState.proExpiringCTA) {
            showExpiring = false
            if (dialogsState.proExpiringCTA != null) {
                delay(1500)
                showExpiring = true
            }
        }

        if(showExpiring && dialogsState.proExpiringCTA != null){
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
                    CTAFeature.Icon(stringResource(R.string.proFeatureListLongerMessages)),
                    CTAFeature.Icon(stringResource(R.string.proFeatureListPinnedConversations)),
                    CTAFeature.Icon(stringResource(R.string.proFeatureListAnimatedDisplayPicture)),
                ),
                positiveButtonText = stringResource(R.string.update),
                negativeButtonText = stringResource(R.string.close),
                onUpgrade = {
                    sendCommand(HomeViewModel.Commands.HideExpiringCTADialog)
                    sendCommand(HomeViewModel.Commands.GotoProSettings(ProSettingsDestination.ChoosePlan))
                },
                onCancel = {
                    sendCommand(HomeViewModel.Commands.HideExpiringCTADialog)
                }
            )
        }

        // we need a delay before displaying this.
        // Setting the delay in the VM does not account for render and it seems to appear immediately
        var showExpired by remember { mutableStateOf(false) }
        LaunchedEffect(dialogsState.proExpiredCTA) {
            showExpired = false
            if (dialogsState.proExpiredCTA) {
                delay(1500)
                showExpired = true
            }
        }

        if (showExpired && dialogsState.proExpiredCTA) {
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
                    CTAFeature.Icon(stringResource(R.string.proFeatureListLongerMessages)),
                    CTAFeature.Icon(stringResource(R.string.proFeatureListPinnedConversations)),
                    CTAFeature.Icon(stringResource(R.string.proFeatureListAnimatedDisplayPicture)),
                ),
                positiveButtonText = stringResource(R.string.renew),
                negativeButtonText = stringResource(R.string.cancel),
                onUpgrade = {
                    sendCommand(HomeViewModel.Commands.HideExpiredCTADialog)
                    sendCommand(HomeViewModel.Commands.GotoProSettings(ProSettingsDestination.ChoosePlan))
                },
                onCancel = {
                    sendCommand(HomeViewModel.Commands.HideExpiredCTADialog)
                }
            )
        }

        // we need a delay before displaying this.
        // Setting the delay in the VM does not account for render and it seems to appear immediately
        var showDonation by remember { mutableStateOf(false) }
        LaunchedEffect(dialogsState.donationCTA) {
            showDonation = false
            if (dialogsState.donationCTA) {
                delay(1500)
                showDonation = true
            }
        }

        if (showDonation && dialogsState.donationCTA) {
            val context = LocalContext.current
            AnimatedSessionProCTA(
                heroImageBg = R.drawable.cta_hero_generic_bg,
                heroImageAnimatedFg = R.drawable.cta_hero_generic_fg,
                title = stringResource(R.string.proExpired), //todo DONATION need crowdin strings
                showProBadge = false,
                text = Phrase.from(context,R.string.proExpiredDescription)
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format()
                    .toString(),//todo DONATION need crowdin strings
                positiveButtonText = stringResource(R.string.donate),
                negativeButtonText = stringResource(R.string.cancel), //todo DONATION need crowdin strings
                onUpgrade = {
                    sendCommand(HideDonationCTADialog)
                    sendCommand(ShowDonationConfirmation)
                },
                onCancel = {
                    sendCommand(HideDonationCTADialog)
                }
            )
        }

        if(dialogsState.showUrlDialog != null){
            OpenURLAlertDialog(
                url = dialogsState.showUrlDialog,
                onLinkOpened = { sendCommand(OnLinkOpened(dialogsState.showUrlDialog)) },
                onLinkCopied = { sendCommand(OnLinkCopied(dialogsState.showUrlDialog)) },
                onDismissRequest = { sendCommand(HideUrlDialog) }
            )
        }
    }
}


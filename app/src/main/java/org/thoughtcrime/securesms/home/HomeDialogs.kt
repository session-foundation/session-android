package org.thoughtcrime.securesms.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.squareup.phrase.Phrase
import kotlinx.coroutines.delay
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.GotoProSettings
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HandleUserProfileCommand
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HideDonationCTADialog
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HideExpiredCTADialog
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HidePinCTADialog
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HideSimpleDialog
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HideUrlDialog
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.HideUserProfileModal
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.OnLinkCopied
import org.thoughtcrime.securesms.home.HomeViewModel.Commands.OnLinkOpened
import org.thoughtcrime.securesms.home.startconversation.StartConversationSheet
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination
import org.thoughtcrime.securesms.ui.dialog.AlertDialog
import org.thoughtcrime.securesms.ui.AnimatedSessionProCTA
import org.thoughtcrime.securesms.ui.BottomFadingEdgeBox
import org.thoughtcrime.securesms.ui.CTAFeature
import org.thoughtcrime.securesms.ui.CTAImage
import org.thoughtcrime.securesms.ui.dialog.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.PinProCTA
import org.thoughtcrime.securesms.ui.UserProfileModal
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.dialog.BasicSessionAlertDialog
import org.thoughtcrime.securesms.ui.dialog.DialogBg
import org.thoughtcrime.securesms.ui.dialog.LinkAlertDialog
import org.thoughtcrime.securesms.ui.openUrl
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.shimmerOverlay
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionMaterialTheme
import org.thoughtcrime.securesms.ui.theme.blackAlpha40
import org.thoughtcrime.securesms.util.DonationManager.Companion.URL_DONATE

@Composable
fun HomeDialogs(
    dialogsState: HomeViewModel.DialogsState,
    sendCommand: (HomeViewModel.Commands) -> Unit
) {
    SessionMaterialTheme {
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
        var showExpiring by retain { mutableStateOf(false) }
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
        var showExpired by retain { mutableStateOf(false) }
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
                    sendCommand(HideExpiredCTADialog)
                    sendCommand(GotoProSettings(ProSettingsDestination.ChoosePlan))
                },
                onCancel = {
                    sendCommand(HideExpiredCTADialog)
                }
            )
        }

        // we need a delay before displaying this.
        // Setting the delay in the VM does not account for render and it seems to appear immediately
        var showDonation by retain { mutableStateOf(false) }
        LaunchedEffect(dialogsState.donationCTA) {
            showDonation = false
            if (dialogsState.donationCTA) {
                delay(1500)
                showDonation = true
            }
        }

        if (showDonation && dialogsState.donationCTA) {
            DonationDialog(
                sendCommand = sendCommand
            )
        }

        if(dialogsState.urlDialog != null){
            LinkAlertDialog(
                data = dialogsState.urlDialog,
                onLinkOpened = { sendCommand(OnLinkOpened(dialogsState.urlDialog.url)) },
                onLinkCopied = { sendCommand(OnLinkCopied(dialogsState.urlDialog.url)) },
                onDismissRequest = { sendCommand(HideUrlDialog) },
                openOrJoinCommunity = { sendCommand(HomeViewModel.Commands.OpenOrJoinCommunity(it))}
            )
        }
    }
}

@Composable
fun DonationDialog(
    sendCommand: (HomeViewModel.Commands) -> Unit
) {
    val context = LocalContext.current
    val onCancel = {
        sendCommand(HideDonationCTADialog)
    }

    val title = Phrase.from(context,R.string.donateSessionAppealTitle)
        .put(StringSubstitutionConstants.DONATE_APPEAL_KEY, NonTranslatableStringConstants.DONATE_APPEAL_NAME)
        .format()

    val text = Phrase.from(context,R.string.donateSessionAppealDescription)
        .put(StringSubstitutionConstants.APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
        .format()

    val titleColor: Color = LocalColors.current.text

    BasicSessionAlertDialog(
        onDismissRequest = onCancel,
        content = {
            DialogBg {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val heroMaxHeight = maxHeight * 0.4f
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // hero image
                        BottomFadingEdgeBox(
                            modifier = Modifier.heightIn(max = heroMaxHeight),
                            fadingEdgeHeight = 70.dp,
                            fadingColor = LocalColors.current.backgroundSecondary,
                            content = { _ ->
                                CTAImage(heroImage = R.drawable.cta_hero_donation)
                            },
                        )

                        // content
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(LocalDimensions.current.smallSpacing)
                        ) {
                            // title
                            Text(
                                modifier = Modifier
                                    .qaTag(R.string.qa_pro_badge_text)
                                    .align(Alignment.CenterHorizontally),
                                text = annotatedStringResource(title),
                                textAlign = TextAlign.Center,
                                style = LocalType.current.h5.copy(color = titleColor),
                            )

                            Spacer(Modifier.height(LocalDimensions.current.contentSpacing))

                            // main message
                            Text(
                                modifier = Modifier
                                    .qaTag(R.string.qa_cta_body)
                                    .align(Alignment.CenterHorizontally),
                                text = annotatedStringResource(text),
                                textAlign = TextAlign.Center,
                                style = LocalType.current.base.copy(
                                    color = LocalColors.current.textSecondary
                                )
                            )

                            Spacer(Modifier.height(LocalDimensions.current.contentSpacing))

                            // buttons
                            Row(
                                Modifier.height(IntrinsicSize.Min)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(
                                    LocalDimensions.current.xsSpacing,
                                    Alignment.CenterHorizontally
                                ),
                            ) {
                                AccentFillButtonRect(
                                    modifier = Modifier
                                        .qaTag(R.string.qa_cta_button_positive)
                                        .shimmerOverlay(),
                                    text = stringResource(R.string.donateSessionAppealReadMore),
                                    onClick =  {
                                        context.openUrl(URL_DONATE)
                                        sendCommand(HomeViewModel.Commands.OnDonationLinkClicked)
                                    }
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(LocalDimensions.current.xxsSpacing)
                            .background(
                                color = blackAlpha40,
                                shape = CircleShape,
                            ).size(34.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_x),
                            tint = Color.White,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
            }
        }
    )
}


@Preview
@Composable
fun PreviewDonationDialog() {
    PreviewTheme {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            DonationDialog {}
        }
    }
}


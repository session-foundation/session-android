package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DEVICE_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_ACCOUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.SubscriptionDetails
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun CancelPlanNonOriginating(
    subscriptionDetails: SubscriptionDetails,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val context = LocalContext.current

    BaseNonOriginatingProSettingsScreen(
        disabled = true,
        onBack = onBack,
        headerTitle = Phrase.from(context.getText(R.string.proCancelSorry))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
        buttonText = Phrase.from(context.getText(R.string.openPlatformWebsite))
            .put(PLATFORM_KEY, subscriptionDetails.getPlatformDisplayName())
            .format().toString(),
        dangerButton = true,
        onButtonClick = {
            sendCommand(ShowOpenUrlDialog(subscriptionDetails.subscriptionUrl))
        },
        contentTitle = stringResource(R.string.proCancellation),
        contentDescription = Phrase.from(context.getText(R.string.proCancellationDescription))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(PLATFORM_ACCOUNT_KEY, subscriptionDetails.platformAccount)
            .format(),
        linkCellsInfo = stringResource(R.string.proCancellationOptions),
        linkCells = listOf(
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onDevice))
                    .put(DEVICE_TYPE_KEY, subscriptionDetails.device)
                    .format(),
                info = Phrase.from(context.getText(R.string.onDeviceCancelDescription))
                    .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
                    .put(DEVICE_TYPE_KEY, subscriptionDetails.device)
                    .put(PLATFORM_ACCOUNT_KEY, subscriptionDetails.platformAccount)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .format(),
                iconRes = R.drawable.ic_smartphone
            ),
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onPlatformWebsite))
                    .put(PLATFORM_KEY, subscriptionDetails.getPlatformDisplayName())
                    .format(),
                info = Phrase.from(context.getText(R.string.requestRefundPlatformWebsite))
                    .put(PLATFORM_KEY, subscriptionDetails.getPlatformDisplayName())
                    .put(PLATFORM_ACCOUNT_KEY, subscriptionDetails.platformAccount)
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .format(),
                iconRes = R.drawable.ic_globe
            )
        )
    )
}

@Preview
@Composable
private fun PreviewUpdatePlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        val context = LocalContext.current
        CancelPlanNonOriginating (
            subscriptionDetails = SubscriptionDetails(
                device = "iOS",
                store = "Apple App Store",
                platform = "Apple",
                platformAccount = "Apple Account",
                subscriptionUrl = "https://www.apple.com/account/subscriptions",
                refundUrl = "https://www.apple.com/account/subscriptions",
            ),
            sendCommand = {},
            onBack = {},
        )
    }
}
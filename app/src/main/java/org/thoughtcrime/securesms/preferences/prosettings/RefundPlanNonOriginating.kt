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
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.previewAutoRenewingApple
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RefundPlanNonOriginating(
    subscription: ProStatus.Active,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val context = LocalContext.current

    BaseNonOriginatingProSettingsScreen(
        disabled = true,
        onBack = onBack,
        headerTitle = stringResource(R.string.proRefundDescription),
        buttonText = Phrase.from(context.getText(R.string.openPlatformWebsite))
            .put(PLATFORM_KEY, subscription.providerData.platform)
            .format().toString(),
        dangerButton = true,
        onButtonClick = {
            sendCommand(ShowOpenUrlDialog(subscription.providerData.refundSupportUrl))
        },
        contentTitle = Phrase.from(context.getText(R.string.proRefunding))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
        contentDescription = Phrase.from(context.getText(R.string.proPlanPlatformRefund))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(PLATFORM_STORE_KEY, subscription.providerData.store)
            .put(PLATFORM_ACCOUNT_KEY, subscription.providerData.platformAccount)
            .format(),
        linkCellsInfo = stringResource(R.string.refundRequestOptions),
        linkCells = listOf(
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onDevice))
                    .put(DEVICE_TYPE_KEY, subscription.providerData.device)
                    .format(),
                info = Phrase.from(context.getText(R.string.proRefundAccountDevice))
                    .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
                    .put(DEVICE_TYPE_KEY, subscription.providerData.device)
                    .put(PLATFORM_ACCOUNT_KEY, subscription.providerData.platformAccount)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format(),
                iconRes = R.drawable.ic_smartphone
            ),
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onPlatformWebsite))
                    .put(PLATFORM_KEY, subscription.providerData.platform)
                    .format(),
                info = Phrase.from(context.getText(R.string.requestRefundPlatformWebsite))
                    .put(PLATFORM_KEY, subscription.providerData.platform)
                    .put(PLATFORM_ACCOUNT_KEY, subscription.providerData.platformAccount)
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
        RefundPlanNonOriginating (
            subscription = previewAutoRenewingApple,
            sendCommand = {},
            onBack = {},
        )
    }
}
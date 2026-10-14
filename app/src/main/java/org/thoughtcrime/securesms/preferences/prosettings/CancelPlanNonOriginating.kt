package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import org.session.libsession.utilities.Phrase
import network.loki.messenger.R
import org.thoughtcrime.securesms.pro.PaymentProviderMetadata
import org.session.libsession.utilities.StringSubstitutionConstants.DEVICE_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_ACCOUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.getPlatformDisplayName
import org.thoughtcrime.securesms.pro.previewAppleMetaData
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun CancelPlanNonOriginating(
    providerData: PaymentProviderMetadata,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val context = LocalContext.current

    BaseNonOriginatingProSettingsScreen(
        screenQaTag = R.string.qa_pro_screen_cancel_plan_non_originating,
        disabled = true,
        onBack = onBack,
        headerTitle = Phrase.from(context.getText(R.string.proCancelSorry))
            .format().toString(),
        buttonText = Phrase.from(context.getText(R.string.openPlatformWebsite))
            .put(PLATFORM_KEY, providerData.getPlatformDisplayName())
            .format().toString(),
        dangerButton = true,
        onButtonClick = {
            sendCommand(ShowOpenUrlDialog(providerData.cancelSubscriptionUrl))
        },
        contentTitle = stringResource(R.string.proCancellation),
        contentDescription = Phrase.from(context.getText(R.string.proCancellationDescription))
            .put(PLATFORM_ACCOUNT_KEY, providerData.platformAccount)
            .format(),
        linkCellsInfo =
            Phrase.from(context.getText(R.string.proCancellationOptions))
                .format().toString(),
        linkCells = listOf(
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onDevice))
                    .put(DEVICE_TYPE_KEY, providerData.device)
                    .format(),
                info = Phrase.from(context.getText(R.string.onDeviceCancelDescription))
                    .put(DEVICE_TYPE_KEY, providerData.device)
                    .put(PLATFORM_ACCOUNT_KEY, providerData.platformAccount)
                    .format(),
                iconRes = R.drawable.ic_smartphone,
                qaTag = R.string.qa_pro_link_cell_device,
                titleQaTag = R.string.qa_pro_link_cell_device_title,
                descriptionQaTag = R.string.qa_pro_link_cell_device_description,
            ),
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onPlatformWebsite))
                    .put(PLATFORM_KEY, providerData.getPlatformDisplayName())
                    .format(),
                info = Phrase.from(context.getText(R.string.cancelProPlatformStore))
                    .put(PLATFORM_STORE_KEY, providerData.store)
                    .put(PLATFORM_ACCOUNT_KEY, providerData.platformAccount)
                    .format(),
                iconRes = R.drawable.ic_globe,
                qaTag = R.string.qa_pro_link_cell_website,
                titleQaTag = R.string.qa_pro_link_cell_website_title,
                descriptionQaTag = R.string.qa_pro_link_cell_website_description,
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
            providerData = previewAppleMetaData,
            sendCommand = {},
            onBack = {},
        )
    }
}
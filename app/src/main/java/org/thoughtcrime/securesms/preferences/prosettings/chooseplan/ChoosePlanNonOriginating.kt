package org.thoughtcrime.securesms.preferences.prosettings.chooseplan

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import org.session.libsession.utilities.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.CURRENT_PLAN_LENGTH_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DEVICE_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_ACCOUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE_KEY
import org.thoughtcrime.securesms.preferences.prosettings.BaseNonOriginatingProSettingsScreen
import org.thoughtcrime.securesms.preferences.prosettings.NonOriginatingLinkCellData
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.getPlatformDisplayName
import org.thoughtcrime.securesms.pro.previewAutoRenewingApple
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ChoosePlanNonOriginating(
    subscription: ProStatus.Active,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val context = LocalContext.current

    val platformOverride = subscription.providerData.getPlatformDisplayName()

    val headerTitle = when(subscription) {
        is ProStatus.Active.Expiring -> Phrase.from(context.getText(R.string.proAccessExpireDate))
            .put(DATE_KEY, subscription.renewingAtFormatted())
            .format()

        is ProStatus.Active.AutoRenewing -> Phrase.from(context.getText(R.string.proAccessActivatedAutoShort))
            .put(CURRENT_PLAN_LENGTH_KEY,
                DateUtils.getLocalisedProPlanLength(context, subscription.duration))
            .put(DATE_KEY, subscription.renewingAtFormatted())
            .format()

        else -> ""
    }

    BaseNonOriginatingProSettingsScreen(
        disabled = false,
        onBack = onBack,
        headerTitle = headerTitle,
        buttonText = Phrase.from(context.getText(R.string.openPlatformWebsite))
            .put(PLATFORM_KEY, platformOverride)
            .format().toString(),
        dangerButton = false,
        onButtonClick = {
            sendCommand(ShowOpenUrlDialog(subscription.providerData.updateSubscriptionUrl))
        },
        contentTitle = Phrase.from(LocalContext.current, R.string.updateAccess)
            .format().toString(),
        contentDescription = Phrase.from(context.getText(R.string.proAccessSignUp))
            .put(PLATFORM_STORE_KEY, subscription.providerData.store)
            .put(PLATFORM_ACCOUNT_KEY, subscription.providerData.platformAccount)
            .format(),
        linkCellsInfo = Phrase.from(context.getText(R.string.updateAccessTwo))
            .format().toString(),
        linkCells = listOf(
            NonOriginatingLinkCellData(
                title = Phrase.from(context.getText(R.string.onDevice))
                    .put(DEVICE_TYPE_KEY, subscription.providerData.device)
                    .format(),
                info = Phrase.from(context.getText(R.string.onDeviceDescription))
                    .put(DEVICE_TYPE_KEY, subscription.providerData.device)
                    .put(PLATFORM_ACCOUNT_KEY, subscription.providerData.platformAccount)
                    .format(),
                iconRes = R.drawable.ic_smartphone
            ),
            NonOriginatingLinkCellData(
                title = Phrase.from(context.getText(R.string.viaStoreWebsite))
                    .put(PLATFORM_KEY, platformOverride)
                    .format(),
                info = Phrase.from(context.getText(R.string.viaStoreWebsiteDescription))
                    .put(PLATFORM_ACCOUNT_KEY, subscription.providerData.platformAccount)
                    .put(PLATFORM_STORE_KEY, platformOverride)
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
        ChoosePlanNonOriginating (
            subscription = previewAutoRenewingApple,
            sendCommand = {},
            onBack = {},
        )
    }
}
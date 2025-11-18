package org.thoughtcrime.securesms.preferences.prosettings.chooseplan

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.squareup.phrase.Phrase
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.BUILD_VARIANT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.ICON_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_ACCOUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE2_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.BaseNonOriginatingProSettingsScreen
import org.thoughtcrime.securesms.preferences.prosettings.NonOriginatingLinkCellData
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.getPlatformDisplayName
import org.thoughtcrime.securesms.pro.previewExpiredApple
import org.thoughtcrime.securesms.ui.components.iconExternalLink
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ChoosePlanNoBilling(
    subscription: ProStatus,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val context = LocalContext.current

    val defaultGoogleStore = ProStatusManager.DEFAULT_GOOGLE_STORE
    val defaultAppleStore = ProStatusManager.DEFAULT_APPLE_STORE

    val headerTitle = when(subscription) {
        is ProStatus.Expired -> Phrase.from(context.getText(R.string.proAccessRenewStart))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .format()

        is ProStatus.NeverSubscribed -> Phrase.from(context.getText(R.string.proUpgradeAccess))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .format()

        else -> ""
    }

    val contentTitle = when(subscription) {
        is ProStatus.Expired -> Phrase.from(context.getText(R.string.renewingPro))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString()

        is ProStatus.NeverSubscribed-> Phrase.from(context.getText(R.string.proUpgradingTo))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString()

        else -> ""
    }

    val contentDescription: CharSequence = when(subscription) {
        is ProStatus.Expired -> Phrase.from(context.getText(R.string.proRenewingNoAccessBilling))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(PLATFORM_STORE_KEY, defaultGoogleStore)
            .put(PLATFORM_STORE2_KEY, defaultAppleStore)
            .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
            .put(BUILD_VARIANT_KEY, when (BuildConfig.FLAVOR) {
                "fdroid" -> "F-Droid Store"
                "huawei" -> "Huawei App Gallery"
                else -> "APK"
            })
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(PLATFORM_STORE_KEY, defaultGoogleStore)
            .put(PLATFORM_STORE2_KEY, defaultAppleStore)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(ICON_KEY, iconExternalLink)
            .format()

        is ProStatus.NeverSubscribed -> Phrase.from(context.getText(R.string.proUpgradeNoAccessBilling))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(PLATFORM_STORE_KEY, defaultGoogleStore)
            .put(PLATFORM_STORE2_KEY, defaultAppleStore)
            .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
            .put(BUILD_VARIANT_KEY, when (BuildConfig.FLAVOR) {
                "fdroid" -> "F-Droid Store"
                "huawei" -> "Huawei App Gallery"
                else -> "APK"
            })
            .put(ICON_KEY, iconExternalLink)
            .format()

        else -> ""
    }
    
    val cellsInfo = when(subscription) {
        is ProStatus.Expired -> stringResource(R.string.proOptionsRenewalSubtitle)
        is ProStatus.NeverSubscribed -> stringResource(R.string.proUpgradeOptionsTwo)
        else -> ""
    }

    val cell1Text: CharSequence = when(subscription) {
        is ProStatus.Expired -> Phrase.from(context.getText(R.string.proRenewDesktopLinked))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
            .put(PLATFORM_STORE_KEY, defaultGoogleStore)
            .put(PLATFORM_STORE2_KEY, defaultAppleStore)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .format()

        is ProStatus.NeverSubscribed -> Phrase.from(context.getText(R.string.proUpgradeDesktopLinked))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
            .put(PLATFORM_STORE_KEY, defaultGoogleStore)
            .put(PLATFORM_STORE2_KEY, defaultAppleStore)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .format()

        else -> ""
    }

    val cell2Text: CharSequence = when(subscription) {
        is ProStatus.Expired -> Phrase.from(context.getText(R.string.proNewInstallationDescription))
            .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
            .put(PLATFORM_STORE_KEY, defaultGoogleStore)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format()

        is ProStatus.NeverSubscribed -> Phrase.from(context.getText(R.string.proNewInstallationUpgrade))
            .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
            .put(PLATFORM_STORE_KEY, defaultGoogleStore)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format()

        else -> ""
    }

    val cells: List<NonOriginatingLinkCellData> = buildList {
        // cell 1
        add(
            NonOriginatingLinkCellData(
                title = stringResource(R.string.onLinkedDevice),
                info = cell1Text,
                iconRes = R.drawable.ic_link
            )
        )

        // cell 2
        add(
            NonOriginatingLinkCellData(
                title = stringResource(R.string.proNewInstallation),
                info = cell2Text,
                iconRes = R.drawable.ic_smartphone
            )
        )

        // optional cell 3
        if(subscription is ProStatus.Expired) {
            add(
                NonOriginatingLinkCellData(
                    title = Phrase.from(context.getText(R.string.onPlatformStoreWebsite))
                        .put(PLATFORM_STORE_KEY, subscription.subscriptionDetails.getPlatformDisplayName())
                        .format(),
                    info = Phrase.from(context.getText(R.string.proAccessRenewPlatformWebsite))
                        .put(PLATFORM_KEY, subscription.subscriptionDetails.getPlatformDisplayName())
                        .put(PLATFORM_ACCOUNT_KEY, subscription.subscriptionDetails.platformAccount)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format(),
                    iconRes = R.drawable.ic_globe
                )
            )
        }
    }


    BaseNonOriginatingProSettingsScreen(
        disabled = false,
        onBack = onBack,
        headerTitle = headerTitle,
        buttonText = if(subscription is ProStatus.Expired) Phrase.from(context.getText(R.string.openPlatformWebsite))
            .put(PLATFORM_KEY, subscription.subscriptionDetails.getPlatformDisplayName())
            .format().toString()
        else null,
        dangerButton = false,
        onButtonClick = {
            if(subscription is ProStatus.Expired) {
                sendCommand(ShowOpenUrlDialog(subscription.subscriptionDetails.updateSubscriptionUrl))
            }
        },
        contentTitle = contentTitle,
        contentDescription = contentDescription,
        contentClick = {
            sendCommand(ShowOpenUrlDialog("https://getsession.org/pro-roadmap"))
        },
        linkCellsInfo = cellsInfo,
        linkCells = cells
    )
}


@Preview
@Composable
private fun PreviewNonOrigExpiredUpdatePlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        val context = LocalContext.current
        ChoosePlanNoBilling (
            subscription = previewExpiredApple,
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PreviewNoBiilingBrandNewPlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        val context = LocalContext.current
        ChoosePlanNoBilling (
            subscription = ProStatus.NeverSubscribed,
            sendCommand = {},
            onBack = {},
        )
    }
}
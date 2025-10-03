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
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.BaseNonOriginatingProSettingsScreen
import org.thoughtcrime.securesms.preferences.prosettings.NonOriginatingLinkCellData
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.SubscriptionType
import org.thoughtcrime.securesms.pro.subscription.SubscriptionDetails
import org.thoughtcrime.securesms.ui.components.iconExternalLink
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ChoosePlanNoBilling(
    subscription: SubscriptionType,
    subscriptionDetails: SubscriptionDetails?,
    platformOverride: String, // this property is here because different scenario will require different property to be used for this string: some will use the platform, others will use the platformStore
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val context = LocalContext.current

    //todo PRO cater for NEVER SUBSCRIBED here

    //todo PRO currently teh same key are used so we can't display both in the same string - need to update crowdin
    val defaultGoogleStore = ProStatusManager.DEFAULT_GOOGLE_STORE
    val defaultAppleStore = ProStatusManager.DEFAULT_APPLE_STORE

    val headerTitle = when(subscription) {
        is SubscriptionType.Expired -> Phrase.from(context.getText(R.string.proPlanRenewStart))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .format()

        else -> ""
    }

    val contentTitle = when(subscription) {
        is SubscriptionType.Expired -> Phrase.from(context.getText(R.string.renewingPro))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString()
        else -> ""
    }

    val contentDescription: CharSequence = when(subscription) {
        is SubscriptionType.Expired -> Phrase.from(context.getText(R.string.proRenewingNoAccessBilling))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(PLATFORM_STORE_KEY, defaultGoogleStore)
            .put(PLATFORM_STORE_KEY, defaultAppleStore)
            .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
            .put(BUILD_VARIANT_KEY, when (BuildConfig.FLAVOR) {
                "fdroid" -> "F-Droid Store"
                "huawei" -> "Huawei App Gallery"
                else -> "APK"
            })
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(PLATFORM_STORE_KEY, defaultGoogleStore)
            .put(PLATFORM_STORE_KEY, defaultAppleStore)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(ICON_KEY, iconExternalLink)
            .format()

        else -> ""
    }
    
    val cellsInfo = when(subscription) {
        is SubscriptionType.Expired -> stringResource(R.string.proOptionsRenewalSubtitle)
        else -> ""
    }

    val cells: List<NonOriginatingLinkCellData> = when(subscription) {
        is SubscriptionType.Expired -> buildList {
            addAll(listOf(
                NonOriginatingLinkCellData(
                    title = stringResource(R.string.onLinkedDevice),
                    info = Phrase.from(context.getText(R.string.proPlanRenewDesktopLinked))
                        .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
                        .put(PLATFORM_STORE_KEY, defaultGoogleStore)
                        .put(PLATFORM_STORE_KEY, defaultAppleStore)
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .format(),
                    iconRes = R.drawable.ic_link
                ),
                NonOriginatingLinkCellData(
                    title = stringResource(R.string.proNewInstallation),
                    info = Phrase.from(context.getText(R.string.proNewInstallationDescription))
                        .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
                        .put(PLATFORM_STORE_KEY, defaultGoogleStore)
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .format(),
                    iconRes = R.drawable.ic_smartphone
                )
            ))

            if(subscriptionDetails != null) {
                add(
                    NonOriginatingLinkCellData(
                        title = Phrase.from(context.getText(R.string.onPlatformStoreWebsite))
                            .put(PLATFORM_STORE_KEY, platformOverride)
                            .format(),
                        info = Phrase.from(context.getText(R.string.proPlanRenewPlatformStoreWebsite))
                            .put(PLATFORM_STORE_KEY, platformOverride)
                            .put(PLATFORM_ACCOUNT_KEY, subscriptionDetails.platformAccount)
                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                            .format(),
                        iconRes = R.drawable.ic_globe
                    )
                )
            }
        }

        else -> emptyList()
    }

    BaseNonOriginatingProSettingsScreen(
        disabled = false,
        onBack = onBack,
        headerTitle = headerTitle,
        buttonText = if (subscriptionDetails != null) Phrase.from(context.getText(R.string.openPlatformWebsite))
            .put(PLATFORM_KEY, platformOverride)
            .format().toString()
        else null,
        dangerButton = false,
        onButtonClick = {
            subscriptionDetails?.let {
                sendCommand(ShowOpenUrlDialog(it.subscriptionUrl))
            }
        },
        contentTitle = contentTitle,
        contentDescription = contentDescription,
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
            subscription = SubscriptionType.Expired(nonOriginatingSubscription = null),
            subscriptionDetails = SubscriptionDetails(
                device = "iPhone",
                store = "Apple App Store",
                platform = "Apple",
                platformAccount = "Apple Account",
                subscriptionUrl = "https://www.apple.com/account/subscriptions",
                refundUrl = "https://www.apple.com/account/subscriptions",
            ),
            platformOverride = "Apple",
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
            subscription = SubscriptionType.Expired(nonOriginatingSubscription = null),
            subscriptionDetails = null,
            platformOverride = "Apple",
            sendCommand = {},
            onBack = {},
        )
    }
}
package org.thoughtcrime.securesms.preferences.prosettings

import android.icu.util.MeasureUnit
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
import org.session.libsession.utilities.StringSubstitutionConstants.CURRENT_PLAN_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DEVICE_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_ACCOUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE_KEY
import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.SubscriptionType
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionDetails
import org.thoughtcrime.securesms.pro.subscription.expiryFromNow
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.util.DateUtils
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ChoosePlanNonOriginating(
    subscription: SubscriptionType,
    subscriptionDetails: SubscriptionDetails,
    platformOverride: String, // this property is here because different scenario will require different property to be used for this string: some will use the platform, others will use the platformStore
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val context = LocalContext.current

    val headerTitle = when(subscription) {
        is SubscriptionType.Active.Expiring -> Phrase.from(context.getText(R.string.proPlanExpireDate))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(DATE_KEY, subscription.duration.expiryFromNow())
            .format()

        is SubscriptionType.Active.AutoRenewing -> Phrase.from(context.getText(R.string.proPlanActivatedAutoShort))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(CURRENT_PLAN_KEY, DateUtils.getLocalisedTimeDuration(
                context = context,
                amount = subscription.duration.duration.months,
                unit = MeasureUnit.MONTH
            ))
            .put(DATE_KEY, subscription.duration.expiryFromNow())
            .format()

        //todo PRO cater to EXPIRED and NEVER SUBSCRIBED here too
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
            sendCommand(ShowOpenUrlDialog(subscriptionDetails.subscriptionUrl))
        },
        contentTitle = stringResource(R.string.updatePlan),
        contentDescription = Phrase.from(context.getText(R.string.proPlanSignUp))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(PLATFORM_STORE_KEY, subscriptionDetails.store)
            .put(PLATFORM_ACCOUNT_KEY, subscriptionDetails.platformAccount)
            .format(),
        linkCellsInfo = stringResource(R.string.updatePlanTwo),
        linkCells = listOf(
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onDevice))
                    .put(DEVICE_TYPE_KEY, subscriptionDetails.device)
                    .format(),
                info = Phrase.from(context.getText(R.string.onDeviceDescription))
                    .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
                    .put(DEVICE_TYPE_KEY, subscriptionDetails.device)
                    .put(PLATFORM_ACCOUNT_KEY, subscriptionDetails.platformAccount)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format(),
                iconRes = R.drawable.ic_smartphone
            ),
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.viaStoreWebsite))
                    .put(PLATFORM_KEY, platformOverride)
                    .format(),
                info = Phrase.from(context.getText(R.string.viaStoreWebsiteDescription))
                    .put(PLATFORM_ACCOUNT_KEY, subscriptionDetails.platformAccount)
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
            subscription = SubscriptionType.Active.AutoRenewing(
                proStatus = ProStatus.Pro(
                    visible = true,
                    validUntil = Instant.now() + Duration.ofDays(14),
                ),
                duration = ProSubscriptionDuration.THREE_MONTHS,
                nonOriginatingSubscription = SubscriptionDetails(
                    device = "iPhone",
                    store = "Apple App Store",
                    platform = "Apple",
                    platformAccount = "Apple Account",
                    subscriptionUrl = "https://www.apple.com/account/subscriptions",
                    refundUrl = "https://www.apple.com/account/subscriptions",
                )
            ),
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
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
import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.SubscriptionType
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionDetails
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RefundPlanNonOriginating(
    subscription: SubscriptionType.Active,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val nonOriginatingData = subscription.nonOriginatingSubscription ?: return
    val context = LocalContext.current

    BaseNonOriginatingProSettingsScreen(
        disabled = true,
        onBack = onBack,
        headerTitle = stringResource(R.string.proRefundDescription),
        buttonText = Phrase.from(context.getText(R.string.openPlatformWebsite))
            .put(PLATFORM_KEY, nonOriginatingData.platform)
            .format().toString(),
        dangerButton = true,
        onButtonClick = {
            sendCommand(ShowOpenUrlDialog(nonOriginatingData.refundUrl))
        },
        contentTitle = Phrase.from(context.getText(R.string.proRefunding))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
        contentDescription = Phrase.from(context.getText(R.string.proPlanPlatformRefund))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(PLATFORM_STORE_KEY, nonOriginatingData.store)
            .put(PLATFORM_ACCOUNT_KEY, nonOriginatingData.platformAccount)
            .format(),
        linkCellsInfo = stringResource(R.string.refundRequestOptions),
        linkCells = listOf(
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onDevice))
                    .put(DEVICE_TYPE_KEY, nonOriginatingData.device)
                    .format(),
                info = Phrase.from(context.getText(R.string.proRefundAccountDevice))
                    .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
                    .put(DEVICE_TYPE_KEY, nonOriginatingData.device)
                    .put(PLATFORM_ACCOUNT_KEY, nonOriginatingData.platformAccount)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format(),
                iconRes = R.drawable.ic_smartphone
            ),
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onPlatformWebsite))
                    .put(PLATFORM_KEY, nonOriginatingData.platform)
                    .format(),
                info = Phrase.from(context.getText(R.string.requestRefundPlatformWebsite))
                    .put(PLATFORM_KEY, nonOriginatingData.platform)
                    .put(PLATFORM_ACCOUNT_KEY, nonOriginatingData.platformAccount)
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
            sendCommand = {},
            onBack = {},
        )
    }
}
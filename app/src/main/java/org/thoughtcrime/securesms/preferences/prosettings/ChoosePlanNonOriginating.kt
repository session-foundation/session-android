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
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE_KEY
import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.pro.SubscriptionState
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
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
    subscription: SubscriptionState.Active,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val nonOriginatingData = subscription.nonOriginatingSubscription ?: return
    val context = LocalContext.current

    val headerTitle = when(subscription) {
        is SubscriptionState.Active.Expiring -> Phrase.from(context.getText(R.string.proPlanExpireDate))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(DATE_KEY, subscription.type.expiryFromNow())
            .format()

        else -> Phrase.from(context.getText(R.string.proPlanActivatedAutoShort))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(CURRENT_PLAN_KEY, DateUtils.getLocalisedTimeDuration(
                context = context,
                amount = subscription.type.duration.months,
                unit = MeasureUnit.MONTH
            ))
            .put(DATE_KEY, subscription.type.expiryFromNow())
            .format()
    }

    BaseNonOriginatingProSettingsScreen(
        disabled = false,
        onBack = onBack,
        headerTitle = headerTitle,
        buttonText = Phrase.from(context.getText(R.string.openStoreWebsite))
            .put(PLATFORM_STORE_KEY, nonOriginatingData.store)
            .format().toString(),
        dangerButton = false,
        onButtonClick = {
            //todo PRO implement
        },
        contentTitle = stringResource(R.string.updatePlan),
        contentDescription = Phrase.from(context.getText(R.string.proPlanSignUp))
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(PLATFORM_STORE_KEY, nonOriginatingData.store)
            .put(PLATFORM_ACCOUNT_KEY, nonOriginatingData.platformAccount)
            .format(),
        linkCellsInfo = stringResource(R.string.updatePlanTwo),
        linkCells = listOf(
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onDevice))
                    .put(DEVICE_TYPE_KEY, nonOriginatingData.device)
                    .format(),
                info = Phrase.from(context.getText(R.string.onDeviceDescription))
                    .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
                    .put(DEVICE_TYPE_KEY, nonOriginatingData.device)
                    .put(PLATFORM_ACCOUNT_KEY, nonOriginatingData.platformAccount)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format(),
                iconRes = R.drawable.ic_smartphone
            ),
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.viaStoreWebsite))
                    .put(PLATFORM_STORE_KEY, nonOriginatingData.store)
                    .format(),
                info = Phrase.from(context.getText(R.string.viaStoreWebsiteDescription))
                    .put(PLATFORM_ACCOUNT_KEY, nonOriginatingData.platformAccount)
                    .put(PLATFORM_STORE_KEY, nonOriginatingData.store)
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
            subscription = SubscriptionState.Active.AutoRenewing(
                proStatus = ProStatus.Pro(
                    visible = true,
                    validUntil = Instant.now() + Duration.ofDays(14),
                ),
                type = ProSubscriptionDuration.THREE_MONTHS,
                nonOriginatingSubscription = SubscriptionState.Active.NonOriginatingSubscription(
                    device = "iPhone",
                    store = "Apple App Store",
                    platform = "Apple",
                    platformAccount = "Apple Account",
                    urlSubscription = "https://www.apple.com/account/subscriptions",
                )
            ),
            sendCommand = {},
            onBack = {},
        )
    }
}
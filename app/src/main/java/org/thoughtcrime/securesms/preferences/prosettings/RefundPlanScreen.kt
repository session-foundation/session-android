package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.SubscriptionType
import org.thoughtcrime.securesms.pro.subscription.NoOpSubscriptionManager
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold
import java.time.Duration
import java.time.Instant


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RefundPlanScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    val planData by viewModel.choosePlanState.collectAsState()
    val activePlan = planData.subscriptionType as? SubscriptionType.Active
    if (activePlan == null) {
        onBack()
        return
    }

    val subManager = viewModel.getSubscriptionManager()

    // there are different UI depending on the state
    when {
       // there is an active subscription but from a different platform
        activePlan.nonOriginatingSubscription != null ->
            RefundPlanNonOriginating(
                subscription = planData.subscriptionType as SubscriptionType.Active,
                sendCommand = viewModel::onCommand,
                onBack = onBack,
            )

        // default refund screen
        else -> RefundPlan(
            data = activePlan,
            subscriptionManager = subManager,
            sendCommand = viewModel::onCommand,
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RefundPlan(
    data: SubscriptionType.Active,
    subscriptionManager: SubscriptionManager,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val isWithinQuickRefundWindow = subscriptionManager.isWithinQuickRefundWindow()
    val platform = subscriptionManager.details.platform

    BaseCellButtonProSettingsScreen(
        disabled = true,
        onBack = onBack,
        buttonText = if(isWithinQuickRefundWindow) Phrase.from(context.getText(R.string.openPlatformWebsite))
            .put(PLATFORM_KEY, platform)
            .format().toString()
        else stringResource(R.string.requestRefund),
        dangerButton = true,
        onButtonClick = {
            if(isWithinQuickRefundWindow && !subscriptionManager.quickRefundUrl.isNullOrEmpty()){
                sendCommand(ShowOpenUrlDialog(subscriptionManager.quickRefundUrl))
            } else {
                sendCommand(ShowOpenUrlDialog(subscriptionManager.details.refundUrl))
            }
        },
        title = stringResource(R.string.proRefundDescription),
    ){
        Column {
            Text(
                text = Phrase.from(context.getText(R.string.proRefunding))
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .format().toString(),
                style = LocalType.current.base.bold(),
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))

            Text(
                text = annotatedStringResource(
                    if(isWithinQuickRefundWindow)
                        Phrase.from(context.getText(R.string.proRefundRequestStorePolicies))
                            .put(PLATFORM_KEY, platform)
                            .put(PLATFORM_KEY, platform)
                            .put(PLATFORM_KEY, platform)
                            .put(APP_NAME_KEY, context.getString(R.string.app_name))
                            .format()
                    else Phrase.from(context.getText(R.string.proRefundRequestSessionSupport))
                        .put(APP_NAME_KEY, context.getString(R.string.app_name))
                        .put(APP_NAME_KEY, context.getString(R.string.app_name))
                        .format()
                ),
                style = LocalType.current.base,
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.smallSpacing))

            Text(
                text = stringResource(R.string.important),
                style = LocalType.current.base.bold(),
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))

            Text(
                text = annotatedStringResource(
                    Phrase.from(context.getText(R.string.proImportantDescription))
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format()
                ),
                style = LocalType.current.base,
                color = LocalColors.current.text,
            )
        }
    }
}

@Preview
@Composable
private fun PreviewRefundPlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        RefundPlan(
            data = SubscriptionType.Active.AutoRenewing(
                proStatus = ProStatus.Pro(
                    visible = true,
                    validUntil = Instant.now() + Duration.ofDays(14),
                ),
                duration = ProSubscriptionDuration.THREE_MONTHS,
                nonOriginatingSubscription = null
            ),
            subscriptionManager = NoOpSubscriptionManager(),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PreviewQuickRefundPlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        RefundPlan(
            data = SubscriptionType.Active.AutoRenewing(
                proStatus = ProStatus.Pro(
                    visible = true,
                    validUntil = Instant.now() + Duration.ofDays(14),
                ),
                duration = ProSubscriptionDuration.THREE_MONTHS,
                nonOriginatingSubscription = null
            ),
            subscriptionManager = NoOpSubscriptionManager(),
            sendCommand = {},
            onBack = {},
        )
    }
}



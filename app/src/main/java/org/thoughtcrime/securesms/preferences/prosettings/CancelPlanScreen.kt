package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
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
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.OpenSubscriptionPage
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
fun CancelPlanScreen(
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
            CancelPlanNonOriginating(
                subscription = planData.subscriptionType as SubscriptionType.Active,
                sendCommand = viewModel::onCommand,
                onBack = onBack,
            )

        // default cancel screen
        else -> CancelPlan(
            data = activePlan,
            subscriptionManager = subManager,
            sendCommand = viewModel::onCommand,
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun CancelPlan(
    data: SubscriptionType.Active,
    subscriptionManager: SubscriptionManager,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    BaseCellButtonProSettingsScreen(
        disabled = true,
        onBack = onBack,
        buttonText = Phrase.from(context.getText(R.string.cancelProPlan))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
        dangerButton = true,
        onButtonClick = {
            sendCommand(OpenSubscriptionPage)
        },
        title = Phrase.from(context.getText(R.string.proCancelSorry))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .format().toString(),
    ){
        Column {
            Text(
                text = stringResource(R.string.proCancellation),
                style = LocalType.current.base.bold(),
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))

            Text(
                text = annotatedStringResource(
                    Phrase.from(context.getText(R.string.proCancellationShortDescription))
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
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
private fun PreviewCancelPlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        CancelPlan(
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



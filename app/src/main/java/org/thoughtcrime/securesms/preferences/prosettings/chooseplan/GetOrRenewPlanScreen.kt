package org.thoughtcrime.securesms.preferences.prosettings.chooseplan

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel
import org.thoughtcrime.securesms.pro.SubscriptionType


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GetOrRenewPlanScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    // Renew plan
    val planData by viewModel.choosePlanState.collectAsState()

    when {
        // there are no billing options on this device
        !planData.hasBillingCapacity ->
            ChoosePlanNoBilling(
                subscription = planData.subscriptionType,
                sendCommand = viewModel::onCommand,
                onBack = onBack,
            )

        // default plan chooser
        else -> ChoosePlan(
            planData = planData,
            sendCommand = viewModel::onCommand,
            onBack = onBack,
        )
    }
}



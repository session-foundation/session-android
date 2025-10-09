package org.thoughtcrime.securesms.preferences.prosettings.chooseplan

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel
import org.thoughtcrime.securesms.pro.SubscriptionType


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun UpdatePlanScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    // Update plan
    val planData by viewModel.choosePlanState.collectAsState()
    val subscription = planData.subscriptionType as? SubscriptionType.Active
    // can't update a plan if the subscription isn't currently active
    if(subscription == null){
        onBack()
        return
    }

    val subscriptionManager = viewModel.getSubscriptionManager()

    when {
        // there is an active subscription but from a different platform or from the
        // same platform but a different account
        // or we have no billing APIs
        subscription.subscriptionDetails.isFromAnotherPlatform()
                || !planData.hasValidSubscription
                || !subscriptionManager.supportsBilling ->
            ChoosePlanNonOriginating(
                subscription = planData.subscriptionType as SubscriptionType.Active,
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



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
    val subscription = planData.subscriptionType as? SubscriptionType.Expired
    // can't update a plan if the subscription isn't expired
    if(subscription == null){
        onBack()
        return
    }

    val subscriptionManager = viewModel.getSubscriptionManager()

    // there are different UI depending on the state
    val nonOriginatingSubscription = subscription.nonOriginatingSubscription

    when {
        // there is an active subscription but from a different platform
        nonOriginatingSubscription != null ->
            ChoosePlanNoBilling(
                subscription = planData.subscriptionType,
                subscriptionDetails = nonOriginatingSubscription,
                platformOverride = nonOriginatingSubscription.platform,
                sendCommand = viewModel::onCommand,
                onBack = onBack,
            )

        // there is an active subscription but the existing subscription manager does not have a valid product for this acount account
        !planData.hasValidSubscription  -> {
            ChoosePlanNoBilling(
                subscription = planData.subscriptionType,
                subscriptionDetails = subscriptionManager.details,
                platformOverride = subscriptionManager.details.store,
                sendCommand = viewModel::onCommand,
                onBack = onBack,
            )
        }

        // default plan chooser
        else -> ChoosePlan(
            planData = planData,
            sendCommand = viewModel::onCommand,
            onBack = onBack,
        )
    }
}



package org.thoughtcrime.securesms.preferences.prosettings.chooseplan

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.thoughtcrime.securesms.preferences.prosettings.BaseStateProScreen
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.isFromAnotherPlatform

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ChoosePlanHomeScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) {
        // ensuring we get the latest data here
        // since we can deep link to this screen without going through the pro home screen
        viewModel.ensureChoosePlanState()
    }

    val state by viewModel.choosePlanState.collectAsState()

    BaseStateProScreen(
        state = state,
        onBack = onBack
    ) { planData ->
        // Option 1. ACTIVE Pro subscription
        if(planData.proStatus is ProStatus.Active) {
            val subscription = planData.proStatus

            when {
                // there is an active subscription but from a different platform or from the
                // same platform but a different account
                // or we have no billing APIs
                subscription.providerData.isFromAnotherPlatform()
                        || !planData.hasValidSubscription
                        || !planData.hasBillingCapacity ->
                    ChoosePlanNonOriginating(
                        subscription = planData.proStatus,
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
        } else { // Option 2. Get brand new or Renew plan
            when {
                // there are no billing options on this device
                !planData.hasBillingCapacity ->
                    ChoosePlanNoBilling(
                        subscription = planData.proStatus,
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
    }
}
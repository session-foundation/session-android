package org.thoughtcrime.securesms.preferences.prosettings.chooseplan

import android.widget.Toast
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import network.loki.messenger.R
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel
import org.thoughtcrime.securesms.pro.SubscriptionType
import org.thoughtcrime.securesms.ui.components.CircularProgressIndicator
import org.thoughtcrime.securesms.util.State

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ChoosePlanHomeScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.choosePlanState.collectAsState()

    when(state){
        is State.Error -> {
            // show a toast and go back to pro settings home screen
            Toast.makeText(LocalContext.current, R.string.errorGeneric, Toast.LENGTH_LONG).show()
            onBack()
        }

        is State.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is State.Success -> {
            val planData = (state as State.Success).value

            // Option 1. ACTIVE Pro subscription
            if(planData.subscriptionType is SubscriptionType.Active) {
                val subscription = planData.subscriptionType

                when {
                    // there is an active subscription but from a different platform or from the
                    // same platform but a different account
                    // or we have no billing APIs
                    subscription.subscriptionDetails.isFromAnotherPlatform()
                            || !planData.hasValidSubscription
                            || !planData.hasBillingCapacity ->
                        ChoosePlanNonOriginating(
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
            } else { // Option 2. Get brand new or Renew plan
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
        }
    }
}
package org.thoughtcrime.securesms.preferences.prosettings

import android.annotation.SuppressLint
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.CancelSubscription
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.GetOrRenewPlan
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.Home
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.PlanConfirmation
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.RefundSubscription
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.UpdatePlan
import org.thoughtcrime.securesms.preferences.prosettings.chooseplan.GetOrRenewPlanScreen
import org.thoughtcrime.securesms.preferences.prosettings.chooseplan.UpdatePlanScreen
import org.thoughtcrime.securesms.ui.NavigationAction
import org.thoughtcrime.securesms.ui.ObserveAsEvents
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.horizontalSlideComposable

// Destinations
sealed interface ProSettingsDestination {
    @Serializable
    data object Home: ProSettingsDestination

    @Serializable
    data object UpdatePlan: ProSettingsDestination
    @Serializable
    data object GetOrRenewPlan: ProSettingsDestination

    @Serializable
    data object PlanConfirmation: ProSettingsDestination

    @Serializable
    data object CancelSubscription: ProSettingsDestination

    @Serializable
    data object RefundSubscription: ProSettingsDestination
}

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProSettingsNavHost(
    navigator: UINavigator<ProSettingsDestination>,
    onBack: () -> Unit
){
    SharedTransitionLayout {
        val navController = rememberNavController()
        val scope = rememberCoroutineScope()

        // all screens within the Pro Flow can share the same VM
        val viewModel = hiltViewModel<ProSettingsViewModel>()

        val dialogsState by viewModel.dialogState.collectAsState()

        ObserveAsEvents(flow = navigator.navigationActions) { action ->
            when (action) {
                is NavigationAction.Navigate -> navController.navigate(
                    action.destination
                ) {
                    action.navOptions(this)
                }

                NavigationAction.NavigateUp -> navController.navigateUp()

                is NavigationAction.NavigateToIntent -> {
                    navController.context.startActivity(action.intent)
                }

                is NavigationAction.ReturnResult -> {}
            }
        }

        NavHost(navController = navController, startDestination = Home) {
            // Home
            horizontalSlideComposable<Home> {
                ProSettingsHomeScreen(
                    viewModel = viewModel,
                    onBack = onBack,
                )
            }

            // Subscription plan selection
            horizontalSlideComposable<UpdatePlan> {
                UpdatePlanScreen(
                    viewModel = viewModel,
                    onBack = { scope.launch { navigator.navigateUp() } },
                )
            }
            horizontalSlideComposable<GetOrRenewPlan> {
                GetOrRenewPlanScreen(
                    viewModel = viewModel,
                    onBack = { scope.launch { navigator.navigateUp() } },
                )
            }

            // Subscription plan confirmation
            horizontalSlideComposable<PlanConfirmation> {
                PlanConfirmationScreen(
                    viewModel = viewModel,
                    onBack = { scope.launch { navigator.navigateUp() }},
                )
            }

            // Refund
            horizontalSlideComposable<RefundSubscription> {
                RefundPlanScreen(
                    viewModel = viewModel,
                    onBack = { scope.launch { navigator.navigateUp() }},
                )
            }

            // Cancellation
            horizontalSlideComposable<CancelSubscription> {
                CancelPlanScreen(
                    viewModel = viewModel,
                    onBack = { scope.launch { navigator.navigateUp() }},
                )
            }
        }

        // Dialogs
        ProSettingsDialogs(
            dialogsState = dialogsState,
            sendCommand = viewModel::onCommand,
        )
    }
}
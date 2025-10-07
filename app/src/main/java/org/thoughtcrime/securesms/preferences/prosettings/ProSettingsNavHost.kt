package org.thoughtcrime.securesms.preferences.prosettings

import android.annotation.SuppressLint
import android.os.Parcelable
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
import kotlinx.parcelize.Parcelize
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
sealed interface ProSettingsDestination: Parcelable {
    @Serializable
    @Parcelize
    data object Home: ProSettingsDestination

    @Serializable
    @Parcelize
    data object UpdatePlan: ProSettingsDestination
    @Serializable
    @Parcelize
    data object GetOrRenewPlan: ProSettingsDestination

    @Serializable
    @Parcelize
    data object PlanConfirmation: ProSettingsDestination

    @Serializable
    @Parcelize
    data object CancelSubscription: ProSettingsDestination

    @Serializable
    @Parcelize
    data object RefundSubscription: ProSettingsDestination
}

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProSettingsNavHost(
    navigator: UINavigator<ProSettingsDestination>,
    startDestination: ProSettingsDestination = Home,
    onBack: () -> Unit
){
    SharedTransitionLayout {
        val navController = rememberNavController()
        val scope = rememberCoroutineScope()

        // all screens within the Pro Flow can share the same VM
        val viewModel = hiltViewModel<ProSettingsViewModel>()

        val dialogsState by viewModel.dialogState.collectAsState()

        val handleBack: () -> Unit = {
            if (navController.previousBackStackEntry != null) {
                scope.launch { navigator.navigateUp() }
            } else {
                onBack() // Finish activity if at root
            }
        }


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

        NavHost(navController = navController, startDestination = startDestination) {
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
                    onBack = handleBack,
                )
            }
            horizontalSlideComposable<GetOrRenewPlan> {
                GetOrRenewPlanScreen(
                    viewModel = viewModel,
                    onBack = handleBack,
                )
            }

            // Subscription plan confirmation
            horizontalSlideComposable<PlanConfirmation> {
                PlanConfirmationScreen(
                    viewModel = viewModel,
                    onBack = handleBack,
                )
            }

            // Refund
            horizontalSlideComposable<RefundSubscription> {
                RefundPlanScreen(
                    viewModel = viewModel,
                    onBack = handleBack,
                )
            }

            // Cancellation
            horizontalSlideComposable<CancelSubscription> {
                CancelPlanScreen(
                    viewModel = viewModel,
                    onBack = handleBack,
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
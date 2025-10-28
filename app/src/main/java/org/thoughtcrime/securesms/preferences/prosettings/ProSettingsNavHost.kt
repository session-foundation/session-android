package org.thoughtcrime.securesms.preferences.prosettings

import android.annotation.SuppressLint
import android.os.Parcelable
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
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

@Serializable object ProSettingsGraph

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProSettingsNavHost(
    startDestination: ProSettingsDestination = Home,
    inSheet: Boolean,
    onBack: () -> Unit
){
    val navController = rememberNavController()
    val navigator: UINavigator<ProSettingsDestination> = remember {
        UINavigator<ProSettingsDestination>()
    }

    val handleBack: () -> Unit = {
        if (navController.previousBackStackEntry != null) {
            navController.navigateUp()
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

            NavigationAction.NavigateUp -> handleBack()

            is NavigationAction.NavigateToIntent -> {
                navController.context.startActivity(action.intent)
            }

            is NavigationAction.ReturnResult -> {}
        }
    }

    NavHost(
        navController = navController,
        startDestination = ProSettingsGraph
    ) {
        navigation<ProSettingsGraph>(startDestination = startDestination) {
            // Home
            horizontalSlideComposable<Home> { entry ->
                val viewModel = navController.proGraphViewModel(entry, navigator)
                ProSettingsHomeScreen(
                    viewModel = viewModel,
                    inSheet = inSheet,
                    onBack = onBack,
                )
            }

            // Subscription plan selection
            horizontalSlideComposable<UpdatePlan> { entry ->
                val viewModel = navController.proGraphViewModel(entry, navigator)
                UpdatePlanScreen(
                    viewModel = viewModel,
                    onBack = handleBack,
                )
            }
            horizontalSlideComposable<GetOrRenewPlan> { entry ->
                val viewModel = navController.proGraphViewModel(entry, navigator)
                GetOrRenewPlanScreen(
                    viewModel = viewModel,
                    onBack = handleBack,
                )
            }

            // Subscription plan confirmation
            horizontalSlideComposable<PlanConfirmation> { entry ->
                val viewModel = navController.proGraphViewModel(entry, navigator)
                PlanConfirmationScreen(
                    viewModel = viewModel,
                    onBack = handleBack,
                )
            }

            // Refund
            horizontalSlideComposable<RefundSubscription> { entry ->
                val viewModel = navController.proGraphViewModel(entry, navigator)
                RefundPlanScreen(
                    viewModel = viewModel,
                    onBack = handleBack,
                )
            }

            // Cancellation
            horizontalSlideComposable<CancelSubscription> { entry ->
                val viewModel = navController.proGraphViewModel(entry, navigator)
                CancelPlanScreen(
                    viewModel = viewModel,
                    onBack = handleBack,
                )
            }
        }
    }

    // Dialogs
    // the composable need to wait until the graph has been rendered
    val graphReady = remember(navController.currentBackStackEntryAsState().value) {
        runCatching { navController.getBackStackEntry(ProSettingsGraph) }.getOrNull()
    }
    graphReady?.let { entry ->
        val vm = navController.proGraphViewModel(entry, navigator)
        val dialogsState by vm.dialogState.collectAsState()
        ProSettingsDialogs(dialogsState = dialogsState, sendCommand = vm::onCommand)
    }
}

@Composable
fun NavController.proGraphViewModel(
    entry: androidx.navigation.NavBackStackEntry,
    navigator: UINavigator<ProSettingsDestination>
): ProSettingsViewModel {
    val graphEntry = remember(entry) { getBackStackEntry(ProSettingsGraph) }
    return hiltViewModel<
            ProSettingsViewModel,
            ProSettingsViewModel.Factory
            >(graphEntry) { factory -> factory.create(navigator) }
}
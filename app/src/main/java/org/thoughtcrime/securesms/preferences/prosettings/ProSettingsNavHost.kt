package org.thoughtcrime.securesms.preferences.prosettings

import android.annotation.SuppressLint
import android.os.Parcelable
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.CancelSubscription
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.Home
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.PlanConfirmation
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.RefundSubscription
import org.thoughtcrime.securesms.preferences.prosettings.chooseplan.ChoosePlanHomeScreen
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
    data object ChoosePlan: ProSettingsDestination

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

enum class ProNavHostCustomActions {
    ON_POST_PLAN_CONFIRMATION, ON_POST_CANCELLATION
}

private const val KEY_SCROLL_TOP = "scrollToTop"

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

            is NavigationAction.PerformCustomAction -> {
                when(action.data as? ProNavHostCustomActions){
                    // handle the custom case of dealing with the post "choose plan confirmation"screen
                    ProNavHostCustomActions.ON_POST_PLAN_CONFIRMATION,
                    ProNavHostCustomActions.ON_POST_CANCELLATION -> {
                        // we get here where we either hit back or hit the "ok" button on the plan confirmation screen
                        // if we are in a sheet we need to close it
                        if (inSheet) {
                            onBack()
                        } // otherwise we should clear the stack and head back to the pro settings home screen
                        else {
                            // set a flag to make sure the home screen scroll back to the top
                            runCatching {
                                navController.getBackStackEntry(Home)
                                    .savedStateHandle[KEY_SCROLL_TOP] = true
                            }

                            // try to navigate "back" home is possible
                            val wentBack = navController.popBackStack(route = Home, inclusive = false)

                            if (!wentBack) {
                                // Fallback: if Home wasn't in the back stack
                                navController.navigate(Home){
                                    popUpTo(Home){ inclusive = false }
                                }
                            }
                        }
                    }

                    else -> {}
                }
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

                // check if we have the scroll flag set
                val scrollToTop by entry.savedStateHandle
                    .getStateFlow(KEY_SCROLL_TOP, false)
                    .collectAsState()

                ProSettingsHomeScreen(
                    viewModel = viewModel,
                    inSheet = inSheet,
                    shouldScrollToTop = scrollToTop,
                    onScrollToTopConsumed = {
                        // Reset the flag so it doesn't trigger again on rotation
                        entry.savedStateHandle["scrollToTop"] = false
                    },
                    onBack = onBack,
                )
            }

            // Subscription plan selection
            horizontalSlideComposable<ProSettingsDestination.ChoosePlan> { entry ->
                val viewModel = navController.proGraphViewModel(entry, navigator)
                ChoosePlanHomeScreen(
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
private fun NavController.proGraphViewModel(
    entry: androidx.navigation.NavBackStackEntry,
    navigator: UINavigator<ProSettingsDestination>
): ProSettingsViewModel {
    val graphEntry = remember(entry) { getBackStackEntry(ProSettingsGraph) }
    return hiltViewModel<
            ProSettingsViewModel,
            ProSettingsViewModel.Factory
            >(graphEntry) { factory -> factory.create(navigator) }
}
package org.thoughtcrime.securesms.debugmenu

import android.annotation.SuppressLint
import android.os.Parcelable
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import org.thoughtcrime.securesms.ui.NavigationAction
import org.thoughtcrime.securesms.ui.ObserveAsEvents
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.handleIntent
import org.thoughtcrime.securesms.ui.horizontalSlideComposable

// Destinations
sealed interface DebugMenuDestination: Parcelable {
    @Serializable
    @Parcelize
    data object DebugMenuHome: DebugMenuDestination

    @Serializable
    @Parcelize
    data object DebugMenuLogs: DebugMenuDestination

}

@Serializable object DebugMenuGraph

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DebugMenuNavHost(
    startDestination: DebugMenuDestination = DebugMenuDestination.DebugMenuHome,
    onBack: () -> Unit
){
    val navController = rememberNavController()
    val navigator: UINavigator<DebugMenuDestination> = remember {
        UINavigator<DebugMenuDestination>()
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
                navController.handleIntent(action.intent)
            }

            else -> {}
        }
    }

    NavHost(
        navController = navController,
        startDestination = DebugMenuGraph
    ) {
        navigation<DebugMenuGraph>(startDestination = startDestination) {
            // Home
            horizontalSlideComposable<DebugMenuDestination.DebugMenuHome> { entry ->
                val viewModel = navController.debugGraphViewModel(entry, navigator)

                DebugMenuScreen(
                    viewModel = viewModel,
                    onBack = onBack
                )
            }

            // Logs
            horizontalSlideComposable<DebugMenuDestination.DebugMenuLogs> { entry ->
                val viewModel = navController.debugGraphViewModel(entry, navigator)

                DebugLogScreen(
                    viewModel = viewModel,
                    onBack = handleBack
                )
            }
        }
    }
}

@Composable
private fun NavController.debugGraphViewModel(
    entry: androidx.navigation.NavBackStackEntry,
    navigator: UINavigator<DebugMenuDestination>
): DebugMenuViewModel {
    val graphEntry = remember(entry) { getBackStackEntry(DebugMenuGraph) }
    return hiltViewModel<
            DebugMenuViewModel,
            DebugMenuViewModel.Factory
            >(graphEntry) { factory -> factory.create(navigator) }
}
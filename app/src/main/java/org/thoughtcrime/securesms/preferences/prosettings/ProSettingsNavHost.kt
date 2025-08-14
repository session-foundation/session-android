package org.thoughtcrime.securesms.preferences.prosettings

import android.annotation.SuppressLint
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination.*
import org.thoughtcrime.securesms.ui.NavigationAction
import org.thoughtcrime.securesms.ui.ObserveAsEvents
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.horizontalSlideComposable

// Destinations
sealed interface ProSettingsDestination {
    @Serializable
    data object Home: ProSettingsDestination

    @Serializable
    data object ChooseSubscriptionPlan: ProSettingsDestination
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
                val viewModel = hiltViewModel<ProSettingsViewModel>()

                ProSettingsHomeScreen(
                    viewModel = viewModel,
                    onBack = onBack,
                )
            }

            // Subscription plan selection
            horizontalSlideComposable<ChooseSubscriptionPlan> {

            }
        }
    }
}
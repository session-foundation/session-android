package org.thoughtcrime.securesms.ui

import android.content.Intent
import androidx.navigation.NavOptionsBuilder
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

@ActivityRetainedScoped
class UINavigator<T> @Inject constructor() {
    private val _navigationActions = Channel<NavigationAction<T>>()
    val navigationActions = _navigationActions.receiveAsFlow()

    suspend fun navigate(
        destination: T,
        navOptions: NavOptionsBuilder.() -> Unit = {}
    ) {
        _navigationActions.send(NavigationAction.Navigate(
            destination = destination,
            navOptions = navOptions
        ))
    }

    suspend fun navigateUp() {
        _navigationActions.send(NavigationAction.NavigateUp)
    }

    suspend fun navigateToIntent(intent: Intent) {
        _navigationActions.send(NavigationAction.NavigateToIntent(intent))
    }

    suspend fun returnResult(code: String, value: Boolean) {
        _navigationActions.send(NavigationAction.ReturnResult(code, value))
    }
}

sealed interface NavigationAction<out T> {
    data class Navigate<T>(
        val destination: T,
        val navOptions: NavOptionsBuilder.() -> Unit = {}
    ) : NavigationAction<T>

    data object NavigateUp : NavigationAction<Nothing>

    data class NavigateToIntent(
        val intent: Intent
    ) : NavigationAction<Nothing>

    data class ReturnResult(
        val code: String,
        val value: Boolean
    ) : NavigationAction<Nothing>
}
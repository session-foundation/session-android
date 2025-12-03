package org.thoughtcrime.securesms.ui

import android.content.Intent
import android.widget.Toast
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import network.loki.messenger.R
import org.session.libsignal.utilities.Log
import javax.inject.Inject

class UINavigator<T> () {
    private val _navigationActions = Channel<NavigationAction<T>>()
    val navigationActions = _navigationActions.receiveAsFlow()

    // simple system to avoid navigating too quickly
    private var lastNavigationTime = 0L
    private val navigationDebounceTime = 500L // 500ms debounce

    suspend fun navigate(
        destination: T,
        navOptions: NavOptionsBuilder.() -> Unit = {}
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNavigationTime > navigationDebounceTime) {
            lastNavigationTime = currentTime
            _navigationActions.send(NavigationAction.Navigate(
                destination = destination,
                navOptions = navOptions
            ))
        }
    }

    suspend fun navigateUp() {
        _navigationActions.send(NavigationAction.NavigateUp)
    }

    suspend fun navigateToIntent(intent: Intent) {
        _navigationActions.send(NavigationAction.NavigateToIntent(intent))
    }

    suspend fun sendCustomAction(data: Any){
        _navigationActions.send(NavigationAction.PerformCustomAction(data))
    }

    suspend fun returnResult(code: String, value: Boolean) {
        _navigationActions.send(NavigationAction.ReturnResult(code, value))
    }
}

fun NavController.handleIntent(intent: Intent){
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        val isWebUrl = intent.action == Intent.ACTION_VIEW &&
                intent.data != null &&
                (intent.data?.scheme == "http" || intent.data?.scheme == "https")

        if(isWebUrl) {
            Toast.makeText(context, R.string.browserNotFound, Toast.LENGTH_LONG).show()
            Log.w("Dialog", "No browser found to open link", e)
        }
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

    data class PerformCustomAction(
        val data: Any
    ) : NavigationAction<Nothing>
}
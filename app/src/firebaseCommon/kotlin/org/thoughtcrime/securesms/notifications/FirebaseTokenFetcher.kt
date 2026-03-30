package org.thoughtcrime.securesms.notifications

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class FirebaseTokenFetcher @Inject constructor(
    connectivity: NetworkConnectivity,
    appVisibilityManager: AppVisibilityManager,
    @ManagerScope scope: CoroutineScope,
): TokenFetcher {
    override val token = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            // Listen for different events and try to fetch the token if it doesn't exist.
            merge(
                connectivity.networkAvailable.filter { available -> available },
                appVisibilityManager.isAppVisible,
            ).collect {
                if (token.value == null) {
                    try {
                        onNewToken(fetchToken())
                    } catch (ec: Throwable) {
                        Log.w("FirebaseTokenFetcher", "Failed to fetch token", ec)
                    }
                }
            }
        }
    }

    private suspend fun fetchToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance()
            .token
            .addOnSuccessListener(cont::resume)
            .addOnFailureListener(cont::resumeWithException)
    }

    override fun onNewToken(token: String) {
        Log.d("FirebaseTokenFetcher", "New FCM token: ${token.take(5)}...")
        this.token.value = token
    }

    override suspend fun resetToken() {
        FirebaseMessaging.getInstance().deleteToken().await()
        onNewToken(fetchToken())
    }
}
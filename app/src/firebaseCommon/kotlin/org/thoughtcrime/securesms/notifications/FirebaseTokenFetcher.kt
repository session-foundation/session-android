package org.thoughtcrime.securesms.notifications

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseTokenFetcher @Inject constructor(
    connectivity: NetworkConnectivity,
    appVisibilityManager: AppVisibilityManager,
    @ManagerScope scope: CoroutineScope,
): TokenFetcher {
    override val token = MutableStateFlow<String?>(null)

    private val tokenResetRequest = MutableSharedFlow<TokenResetRequest>(extraBufferCapacity = 1)

    init {
        scope.launch {
            // Listen for different events and try to fetch the token if it doesn't exist.
            merge(
                connectivity.networkAvailable.filter { available -> available },
                appVisibilityManager.isAppVisible,
                tokenResetRequest,
            ).collect {
                if (token.value == null || it == TokenResetRequest) {
                    try {
                        Log.d("FirebaseTokenFetcher", "Fetching Firebase token")
                        onNewToken(fetchToken())
                    } catch (ec: Throwable) {
                        Log.w("FirebaseTokenFetcher", "Failed to fetch token", ec)
                    }
                }
            }
        }
    }

    private suspend fun fetchToken(): String {
        return FirebaseMessaging.getInstance().token.await()
    }

    override fun onNewToken(token: String) {
        Log.d("FirebaseTokenFetcher", "New FCM token: ${token.take(5)}...")
        this.token.value = token
    }

    override suspend fun resetToken() {
        FirebaseMessaging.getInstance().deleteToken().await()

        // We will request a token reset, but we shouldn't wait for it because we could be
        // offline and causing us to wait forever...
        tokenResetRequest.emit(TokenResetRequest)
    }

    private object TokenResetRequest
}
package org.thoughtcrime.securesms.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.session.libsession.messaging.notifications.TokenFetcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoOpTokenFetcher @Inject constructor() : TokenFetcher {
    override val token: StateFlow<String?> = MutableStateFlow(null)

    override fun onNewToken(token: String) {}
    override suspend fun resetToken() {}
}

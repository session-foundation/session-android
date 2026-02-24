package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Manages the lifecycle of the [Poller] instance and the interaction with the poller.
 *
 * This is done by controlling the coroutineScope that runs the poller, listening to the changes in
 * the logged in state.
 */
@Singleton
class PollerManager @Inject constructor(
    private val provider: Provider<Poller>,
) : AuthAwareComponent {
    private val currentPoller = MutableStateFlow<Poller?>(null)

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        val poller = provider.get()
        currentPoller.value = poller
    }

    override fun onLoggedOut() {
        currentPoller.value?.cancel()
    }


    val isPolling: Boolean
        get() = currentPoller.value?.pollState?.value is BasePoller.PollState.Polling

    /**
     * Requests a poll from the current poller.
     *
     * If there's none, it will suspend until one is created.
     */
    suspend fun pollOnce() {
        currentPoller.filterNotNull().first().manualPollOnce()
    }
}
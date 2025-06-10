package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.session.libsession.utilities.TextSecurePreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PollerManager @Inject constructor(
    prefers: TextSecurePreferences,
    provider: Poller.Factory,
) {
    @OptIn(DelicateCoroutinesApi::class)
    private val currentPoller: StateFlow<Poller?> = channelFlow {
        prefers.watchLocalNumber()
            .map { it != null }
            .distinctUntilChanged()
            .collectLatest { loggedIn ->
                if (loggedIn) {
                    coroutineScope {
                        val poller = provider.create(this)
                        send(poller)
                        awaitCancellation()
                    }
                }
            }
    }.stateIn(GlobalScope, SharingStarted.Eagerly, null)

    val isPolling: Boolean
        get() = currentPoller.value?.pollState?.value == Poller.PollState.Polling

    /**
     * Requests a poll from the current poller.
     *
     * If there's no, it will suspend until a poller is created.
     */
    suspend fun pollOnce() {
        currentPoller.filterNotNull().first().requestPollOnce()
    }
}
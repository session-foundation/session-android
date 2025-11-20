package org.thoughtcrime.securesms.pro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.pro.api.GetProDetailsRequest
import org.thoughtcrime.securesms.pro.api.ProApiExecutor
import org.thoughtcrime.securesms.pro.api.ProDetails
import org.thoughtcrime.securesms.pro.api.successOrThrow
import org.thoughtcrime.securesms.pro.db.ProDatabase
import org.thoughtcrime.securesms.util.NetworkConnectivity
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class ProDetailsRepository @Inject constructor(
    private val db: ProDatabase,
    private val apiExecutor: ProApiExecutor,
    private val getProDetailsRequestFactory: GetProDetailsRequest.Factory,
    private val loginStateRepository: LoginStateRepository,
    private val prefs: TextSecurePreferences,
    networkConnectivity: NetworkConnectivity,
    @ManagerScope scope: CoroutineScope,
) {
    sealed interface LoadState {
        val lastUpdated: Pair<ProDetails, Instant>?

        data object Init : LoadState {
            override val lastUpdated: Pair<ProDetails, Instant>?
                get() = null
        }

        data class Loading(
            override val lastUpdated: Pair<ProDetails, Instant>?,
            val waitingForNetwork: Boolean
        ) : LoadState

        data class Loaded(override val lastUpdated: Pair<ProDetails, Instant>) : LoadState
        data class Error(override val lastUpdated: Pair<ProDetails, Instant>?) : LoadState
    }

    private val refreshRequests: SendChannel<Unit>

    val loadState: StateFlow<LoadState>

    init {
        val channel = Channel<Unit>(capacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        refreshRequests = channel
        @Suppress("OPT_IN_USAGE")
        loadState = prefs.flowPostProLaunch {
            loginStateRepository.loggedInState
                .mapNotNull { it?.seeded?.proMasterPrivateKey }
        }.distinctUntilChanged()
            .flatMapLatest { proMasterKey ->
                flow {
                    var last = db.getProDetailsAndLastUpdated()
                    var numRetried = 0

                    while (true) {
                        // Drain all pending requests as we are about to execute a request
                        while (channel.tryReceive().isSuccess) { }

                        var retryingAt: Instant? = null

                        if (last != null && last.second.plusSeconds(MIN_UPDATE_INTERVAL_SECONDS) >= Instant.now()) {
                            Log.d(DebugLogGroup.PRO_DATA.label, "Pro details is fresh enough, skipping fetch")
                            // Last update was recent enough, skip fetching
                            emit(LoadState.Loaded(last))
                        } else {
                            if (!networkConnectivity.networkAvailable.value) {
                                // No network...mark the state and wait for it to come back
                                emit(LoadState.Loading(last, waitingForNetwork = true))
                                networkConnectivity.networkAvailable.first { it }
                            }

                            emit(LoadState.Loading(last, waitingForNetwork = false))

                            // Fetch new details
                            try {
                                Log.d(DebugLogGroup.PRO_DATA.label, "Start fetching Pro details from backend")
                                last = apiExecutor.executeRequest(
                                    request = getProDetailsRequestFactory.create(proMasterKey)
                                ).successOrThrow() to Instant.now()

                                db.updateProDetails(last.first, last.second)

                                Log.d(DebugLogGroup.PRO_DATA.label, "Successfully fetched Pro details from backend")
                                emit(LoadState.Loaded(last))
                                numRetried = 0
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e

                                emit(LoadState.Error(last))

                                // Exponential backoff for retries, capped at 2 minutes
                                val delaySeconds = minOf(10L * (1L shl numRetried), 120L)
                                Log.e(DebugLogGroup.PRO_DATA.label, "Error fetching Pro details from backend, retrying in ${delaySeconds}s", e)

                                retryingAt = Instant.now().plusSeconds(delaySeconds)
                                numRetried++
                            }
                        }


                        // Wait until either a refresh is requested, or it's time to retry
                        select {
                            refreshRequests.onReceiveCatching {
                                Log.d(DebugLogGroup.PRO_DATA.label, "Manual Pro details refresh requested")
                            }

                            if (retryingAt != null) {
                                val delayMillis =
                                    Duration.between(Instant.now(), retryingAt).toMillis()
                                onTimeout(delayMillis) {
                                    Log.d(DebugLogGroup.PRO_DATA.label, "Retrying Pro details fetch after delay")
                                }
                            }
                        }
                    }
                }
            }.stateIn(scope, SharingStarted.Eagerly, LoadState.Init)
    }

    fun requestRefresh() {
        refreshRequests.trySend(Unit)
    }

    companion object {
        private const val MIN_UPDATE_INTERVAL_SECONDS = 120L
    }
}
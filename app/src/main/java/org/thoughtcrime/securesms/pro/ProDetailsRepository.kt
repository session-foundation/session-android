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

typealias ForceRefresh = Boolean

@Singleton
class ProDetailsRepository @Inject constructor(
    private val db: ProDatabase,
    private val apiExecutor: ProApiExecutor,
    private val getProDetailsRequestFactory: GetProDetailsRequest.Factory,
    private val loginStateRepository: LoginStateRepository,
    prefs: TextSecurePreferences,
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

    private val refreshRequests: SendChannel<ForceRefresh>

    val loadState: StateFlow<LoadState>

    init {
        val channel = Channel<ForceRefresh>()

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
                    var forceRefresh = false

                    while (true) {
                        // Drain all pending requests as we are about to execute a request
                        while (true) {
                            val result = channel.tryReceive()
                            when {
                                result.isClosed -> {
                                    Log.w(TAG, "Refresh channel closed, stopping Pro details fetcher")
                                    return@flow
                                }

                                result.isSuccess -> {
                                    forceRefresh = forceRefresh || result.getOrThrow()
                                }

                                else -> break
                            }
                        }

                        var retryingAt: Instant? = null

                        if (!forceRefresh && last != null
                            && last.second.plusSeconds(MIN_UPDATE_INTERVAL_SECONDS) >= Instant.now()) {
                            Log.d(TAG, "Pro details is fresh enough, skipping fetch")
                            // Last update was recent enough, skip fetching
                            emit(LoadState.Loaded(last))
                        } else {
                            if (!networkConnectivity.networkAvailable.value) {
                                // No network...mark the state and wait for the network to be online
                                emit(LoadState.Loading(last, waitingForNetwork = true))

                                networkConnectivity.networkAvailable.first { it }

                                // We might have waited a while for the network to come back
                                // so drain the refresh requests again to avoid blocking the requesters
                                // for too long
                                while (channel.tryReceive().isSuccess) {}
                            }

                            emit(LoadState.Loading(last, waitingForNetwork = false))

                            // Fetch new details
                            try {
                                Log.d(TAG, "Start fetching Pro details from backend")
                                last = apiExecutor.executeRequest(
                                    request = getProDetailsRequestFactory.create(proMasterKey)
                                ).successOrThrow() to Instant.now()

                                db.updateProDetails(last.first, last.second)

                                Log.d(TAG, "Successfully fetched Pro details from backend")
                                emit(LoadState.Loaded(last))
                                numRetried = 0
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e

                                emit(LoadState.Error(last))

                                // Exponential backoff for retries, capped at 2 minutes
                                val delaySeconds = minOf(10L * (1L shl numRetried), 120L)
                                Log.e(TAG, "Error fetching Pro details from backend, retrying in ${delaySeconds}s", e)

                                retryingAt = Instant.now().plusSeconds(delaySeconds)
                                numRetried++
                            }

                            forceRefresh = false
                        }


                        // Wait until either a refresh is requested, or it's time to retry
                        select {
                            refreshRequests.onReceive {
                                Log.d(TAG, "Manual refresh requested: force = $it")
                                forceRefresh = it
                            }

                            if (retryingAt != null) {
                                val delayMillis =
                                    Duration.between(Instant.now(), retryingAt).toMillis()
                                onTimeout(delayMillis) {
                                    Log.d(TAG, "Retrying Pro details fetch after delay")
                                }
                            }
                        }
                    }
                }
            }.stateIn(scope, SharingStarted.Eagerly, LoadState.Init)
    }

    /**
     * Requests a fresh of current user's pro details. By default, if last update is recent enough,
     * no network request will be made. If [force] is true, a network request will be
     * made regardless of the freshness of the last update.
     */
    suspend fun requestRefresh(force: Boolean = false) {
        if ((loadState.value as? LoadState.Loading)?.waitingForNetwork == true) {
            Log.d(TAG, "Currently waiting for network for a fetch, no need to send another request")
            return
        }

        refreshRequests.send(force)
    }

    companion object {
        private const val TAG = "ProDetailsRepository"
        private const val MIN_UPDATE_INTERVAL_SECONDS = 120L
    }
}
package org.thoughtcrime.securesms.pro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.pro.api.GenerateProProofRequest
import org.thoughtcrime.securesms.pro.api.GetProDetailsRequest
import org.thoughtcrime.securesms.pro.api.GetProRevocationRequest
import org.thoughtcrime.securesms.pro.api.ProApiExecutor
import org.thoughtcrime.securesms.pro.api.ProDetails
import org.thoughtcrime.securesms.pro.api.successOrThrow
import org.thoughtcrime.securesms.pro.db.ProDatabase
import org.thoughtcrime.securesms.util.NetworkConnectivity
import org.thoughtcrime.securesms.util.castAwayType
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

typealias PollToken = Channel<Result<Unit>>

@Singleton
class ProStatePoller @Inject constructor(
    loginStateRepository: LoginStateRepository,
    private val connectivity: NetworkConnectivity,
    private val getProDetailsRequestFactory: GetProDetailsRequest.Factory,
    private val generateProProofRequest: GenerateProProofRequest.Factory,
    private val getProRevocationRequestFactory: GetProRevocationRequest.Factory,
    private val proDatabase: ProDatabase,
    private val snodeClock: SnodeClock,
    private val apiExecutor: ProApiExecutor,
    private val proDetailsRepository: ProDetailsRepository,
    prefs: TextSecurePreferences,
    @ManagerScope scope: CoroutineScope,
): OnAppStartupComponent {
    private val manualPollRequest = Channel<PollToken>()

    enum class PollState {
        Init,
        Polling,
        UpToDate,
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val pollState: StateFlow<PollState> = prefs.flowPostProLaunch {
            loginStateRepository
                .loggedInState
                .map { it?.seeded?.proMasterPrivateKey }
        }
        .distinctUntilChanged()
        .flatMapLatest { proMasterPrivateKey ->
            if (proMasterPrivateKey == null) {
                return@flatMapLatest emptyFlow()
            }

            flow {
                var numRetried = 0
                var nextPoll: Instant? = null
                val pollTokens = mutableListOf<PollToken>()

                while (true) {
                    // Wait for network to become available
                    connectivity.networkAvailable.first { it }

                    if (nextPoll != null) {
                        val now = Instant.now()
                        if (now < nextPoll) {
                            val delayMillis = Duration.between(now, nextPoll).toMillis()
                            Log.d(TAG, "Delaying next poll for $delayMillis ms")
                            select {
                                onTimeout(delayMillis) {}
                                manualPollRequest.onReceiveCatching {
                                    if (it.isSuccess) {
                                        pollTokens.add(it.getOrThrow())
                                    }
                                    Log.d(TAG, "Manual poll requested")
                                }
                            }
                        }
                    }

                    // Drain any manual poll requests
                    while (true) {
                        val received = manualPollRequest.tryReceive()
                        if (received.isSuccess) {
                            pollTokens += received.getOrThrow()
                        } else {
                            break
                        }
                    }

                    emit(PollState.Polling)
                    Log.d(TAG, "Start polling Pro state")

                    val result = runCatching {
                        pollOnce(proMasterPrivateKey)
                        emit(PollState.UpToDate)
                        Log.d(TAG, "Pro state polled successful")
                    }

                    pollTokens.forEach { it.trySend(result) }

                    nextPoll = when {
                        result.isSuccess -> {
                            numRetried = 0
                            Instant.now().plusSeconds(POLL_INTERVAL_MINUTES * 60)
                        }

                        result.exceptionOrNull() is CancellationException -> {
                            throw result.exceptionOrNull()!!
                        }

                        else -> {
                            numRetried++
                            val delaySeconds = (POLL_RETRY_INTERVAL_MIN_SECONDS * numRetried * 1.2).toLong()
                                .coerceAtMost(POLL_INTERVAL_MINUTES * 60)

                            Log.e(TAG, "Error polling pro state, retrying in $delaySeconds seconds", result.exceptionOrNull())

                            Instant.now().plusSeconds(delaySeconds)
                        }
                    }
                }
            }
        }
        .stateIn(scope, started = SharingStarted.Eagerly, initialValue = PollState.Init)

    suspend fun requestPollOnceAndWait() {
        val channel = Channel<Result<Unit>>()
        manualPollRequest.send(channel)
        channel.receive().getOrThrow()
    }

    private suspend fun pollOnce(proMasterPrivateKey: ByteArray) {
        val currentProof = proDatabase.getCurrentProProof()

        if (currentProof == null || currentProof.expiryMs <= snodeClock.currentTimeMills()) {
            // Current proof is missing or expired, grab the pro details to decide what to do next
            proDetailsRepository.requestRefresh()

            val details = proDetailsRepository.loadState.mapNotNull { it.lastUpdated }.first().first

            val newProof = if (details.status == ProDetails.DETAILS_STATUS_ACTIVE) {
                Log.d(TAG, "User is active Pro but has no valid proof, generating new proof")
                apiExecutor.executeRequest(
                    request = generateProProofRequest.create(
                        masterPrivateKey = proMasterPrivateKey,
                        rotatingPrivateKey = proDatabase.ensureValidRotatingKeys(snodeClock.currentTime()).ed25519PrivKey
                    )
                ).successOrThrow()
            } else {
                Log.d(TAG, "User is not active pro")
                null
            }

            Log.d(TAG, "Updating current pro proof to $newProof")
            proDatabase.updateCurrentProProof(newProof)
        } else {
            // Current proof is still valid, we just need to check for revocation
            val lastTicket = proDatabase.getLastRevocationTicket()

            val revocations = apiExecutor.executeRequest(
                request = getProRevocationRequestFactory.create(lastTicket)
            ).successOrThrow()

            proDatabase.updateRevocations(
                newTicket = revocations.ticket,
                data = revocations.items
            )

            if (proDatabase.isRevoked(currentProof.genIndexHashHex)) {
                Log.d(TAG, "Current pro proof has been revoked, deleting")
                proDatabase.updateCurrentProProof(null)
            } else {
                Log.d(TAG, "Current pro proof is still valid and not revoked")
            }
        }
    }

    companion object {
        private const val TAG = "ProStatePoller"


        private const val POLL_INTERVAL_MINUTES = 1L
        private const val POLL_RETRY_INTERVAL_MIN_SECONDS = 5L

//        fun schedule(context: Context) {
//            WorkManager.getInstance(context)
//                .enqueueUniquePeriodicWork(
//                    WORK_NAME,
//                    ExistingPeriodicWorkPolicy.KEEP,
//                    androidx.work.PeriodicWorkRequestBuilder<ProStatePoller>(
//                        repeatInterval = Duration.ofMinutes(15)
//                    ).setConstraints(
//                        Constraints(requiredNetworkType = NetworkType.CONNECTED)
//                    ).setInitialDelay(5, TimeUnit.SECONDS)
//                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
//                        .build()
//                )
//        }
//
//        fun cancel(context: Context) {
//            WorkManager.getInstance(context)
//                .cancelUniqueWork(WORK_NAME)
//        }
    }
}
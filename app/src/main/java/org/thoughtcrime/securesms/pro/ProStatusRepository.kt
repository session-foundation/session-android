package org.thoughtcrime.securesms.pro

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.session.libsession.network.SnodeClock
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.dependencies.ManagerScope
import network.loki.messenger.libsession_util.pro.GetProStatusResponse
import org.thoughtcrime.securesms.pro.db.ProDatabase
import org.thoughtcrime.securesms.util.NetworkConnectivity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProStatusRepository @Inject constructor(
    private val application: Application,
    private val db: ProDatabase,
    private val snodeClock: SnodeClock,
    @param:ManagerScope private val scope: CoroutineScope,
    loginStateRepository: LoginStateRepository,
    private val networkConnectivity: NetworkConnectivity,
) {
    sealed interface LoadState {
        val lastUpdated: Pair<GetProStatusResponse, Instant>?

        data object Init : LoadState {
            override val lastUpdated: Pair<GetProStatusResponse, Instant>?
                get() = null
        }

        data class Loading(
            override val lastUpdated: Pair<GetProStatusResponse, Instant>?,
            val waitingForNetwork: Boolean
        ) : LoadState

        /**
         * A status fetch has succeeded — but [confirmedInThisProcess] says whether it was OURS.
         *
         * WorkManager persists a unique work's terminal state, so at process start `watch()` replays
         * the PREVIOUS run's `SUCCEEDED` and this state is reached before we have asked anyone
         * anything. Callers that mean "the backend has confirmed this for us" must check the flag;
         * `Loaded` alone does not mean that and never did.
         */
        data class Loaded(
            override val lastUpdated: Pair<GetProStatusResponse, Instant>,
            val confirmedInThisProcess: Boolean,
        ) : LoadState
        data class Error(override val lastUpdated: Pair<GetProStatusResponse, Instant>?) : LoadState
    }


    val loadState: StateFlow<LoadState> = loginStateRepository.flowWithLoggedInState {
        combine(
            FetchProStatusWorker.watch(application)
                .map { it.state }
                .distinctUntilChanged(),

            networkConnectivity.networkAvailable,

            db.proStatusChangeNotification
                .onStart { emit(Unit) }
                .map { db.getProStatusAndLastUpdated() }
        ) { state, isOnline, last ->
            when (state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> LoadState.Loading(last, waitingForNetwork = !isOnline)
                WorkInfo.State.RUNNING -> LoadState.Loading(last, waitingForNetwork = false)
                WorkInfo.State.SUCCEEDED -> {
                    if (last != null) {
                        Log.d(DebugLogGroup.PRO_DATA.label, "Successfully fetched Pro status from backend")
                        LoadState.Loaded(
                            lastUpdated = last,
                            // The success timestamp is stamped on completion, so "at or after this
                            // process started observing" is exactly "this process confirmed it".
                            confirmedInThisProcess = !last.second.isBefore(processStartedAt),
                        )
                    } else {
                        // This should never happen, but just in case...
                        LoadState.Error(null)
                    }
                }

                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> LoadState.Error(last)
            }
        }
    } .stateIn(scope, SharingStarted.Eagerly, LoadState.Init)


    /**
     * When this process started observing status.
     *
     * Used to tell a fetch WE completed from one restored out of WorkManager's persisted state. Both
     * failure directions are safe: a clock that runs backwards, or a singleton constructed late, make
     * a genuine confirmation read as unconfirmed, which suppresses rather than asserts.
     */
    private val processStartedAt: Instant = snodeClock.currentTime()

    /**
     * Requests a refresh of the current user's Pro status. By default the request is dropped when
     * the last successful fetch is recent enough.
     *
     * [immediate] bypasses that floor, and its caller list is closed — adding a fourth is a
     * cross-client decision:
     *
     *  - #5 manual refresh / recover (`ProSettingsViewModel`) — the user is watching.
     *  - #7 post-purchase poll (`ProStatusManager`) — bounded, the user is waiting on the entitlement.
     *  - #4 while-open grace poll (`ProSettingsViewModel`) — bounded, and mechanical rather than
     *    urgent: its cadence is exactly [MIN_UPDATE_INTERVAL_SECONDS], so leaving it floored would
     *    drop every other tick to timing jitter.
     *
     * Everything else goes through the floor, which is what stops coinciding triggers each costing a
     * fetch.
     */
    /**
     * Whether THIS process has asked for a status fetch yet — in memory, and not the question the
     * persisted timestamp answers. That one is "was the last fetch recent?"; this one is "has this
     * process asked at all?".
     *
     * Do not delete as redundant with the timestamp. The floor is the only refusal that survives a
     * restart, because it reads a value that outlived the process that wrote it, so this is what
     * guarantees a process's first request reaches the network. Without it the Pro settings screen's
     * on-enter refresh is refused on a relaunch inside the interval, leaving the screen with no
     * confirmed status to render and nothing left that would ask: it spins until the interval expires.
     *
     * Cold-start load stays bounded by the 24h startup gate.
     */
    @Volatile
    private var fetchedInThisProcess = false

    fun requestRefresh(immediate: Boolean = false) {
        if (immediate) {
            Log.d(DebugLogGroup.PRO_DATA.label, "Scheduling immediate fetch of Pro status from server")
            fetchedInThisProcess = true
            FetchProStatusWorker.schedule(application, ExistingWorkPolicy.REPLACE)
            return
        }

        // The floor reads the persisted timestamp rather than `loadState`. `loadState` is a
        // StateFlow starting at LoadState.Init, and Init is neither Loading nor Loaded, so the
        // old check was skipped outright until the database combine had emitted — which the
        // startup trigger beats. The result was that the floor never applied to the one fetch it
        // most needed to cover. `pro_status_updated_at` is already written by every successful
        // fetch (ProDatabase.updateProStatus), so there is nothing new to persist.
        //
        // The scope is GlobalScope (Dispatchers.Default), so the read is off the main thread; the
        // callers are all fire-and-forget.
        scope.launch {
            // The ATTEMPT timestamp, not the success one: a failed request that reached the server
            // costs it the same as a successful one, and gating on success made a failing network
            // re-attempt on every trigger and every cold launch — hardest exactly when the server is
            // least able to take it.
            val lastFetchedAt = db.getProStatusLastAttemptAt()
            if (!shouldFetch(
                    immediate = false,
                    fetchedInThisProcess = fetchedInThisProcess,
                    lastFetchedAt = lastFetchedAt,
                    now = snodeClock.currentTime(),
                )
            ) {
                Log.d(DebugLogGroup.PRO_DATA.label, "Pro status are fresh enough, skipping refresh")
                return@launch
            }

            Log.d(DebugLogGroup.PRO_DATA.label, "Scheduling fetch of Pro status from server")
            fetchedInThisProcess = true
            FetchProStatusWorker.schedule(application, ExistingWorkPolicy.REPLACE)
        }
    }


    companion object {
        /**
         * The status freshness floor. Shared cross-client contract: Desktop and iOS use the same 60s,
         * and it moves by agreement across clients or not at all.
         *
         * A scheduled wake depends on this not being crossed. `ProStatusManager`'s `user_expiry`
         * trigger arms two wakes, at the renewal date and at coverage end, and both reach the network
         * through the floored path. A grace period shorter than this floor puts both inside it and the
         * second one's fetch is dropped — reachable only on compressed QA backends, since production
         * grace always includes a ~1h renewal-latency allowance. To exercise the coverage-end wake,
         * override this constant; do not make a scheduled trigger `immediate`.
         */
        const val MIN_UPDATE_INTERVAL_SECONDS = 60L

        /**
         * The whole of the floor decision, over plain values so it can be tested without a
         * database, a clock or WorkManager.
         *
         * Keyed off the timestamp, never off a load-state enum: an absent timestamp means "no
         * successful fetch on record", which is a reason to fetch, whereas a cold start's initial enum
         * value is neither `Loading` nor `Loaded` and so falls outside any test against those two.
         *
         * Drop-on-fresh, not re-arm (spec §4): a caller whose request is dropped here does not get a
         * later one scheduled on its behalf. The proof loop is deliberately the opposite.
         *
         * [fetchedInThisProcess] is the second exemption — see its own doc for why the two are not
         * redundant.
         */
        fun shouldFetch(
            immediate: Boolean,
            fetchedInThisProcess: Boolean,
            lastFetchedAt: Instant?,
            now: Instant,
        ): Boolean =
            immediate ||
                !fetchedInThisProcess ||
                lastFetchedAt == null ||
                !lastFetchedAt.plusSeconds(MIN_UPDATE_INTERVAL_SECONDS).isAfter(now)
    }
}
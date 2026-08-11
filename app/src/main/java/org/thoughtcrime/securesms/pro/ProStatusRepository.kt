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
     * [immediate] bypasses that floor. It is ONE mechanism with a closed list of sanctioned
     * callers — adding a fourth is a cross-client decision, not a local one:
     *
     *  - **#5 manual refresh / recover** (`ProSettingsViewModel`) — the user is watching.
     *  - **#7 post-purchase poll** (`ProStatusManager.pollProStatusAfterPurchase`) — bounded, and
     *    the user is waiting on the entitlement.
     *  - **#4 while-open grace poll** (`ProSettingsViewModel`) — bounded and self-terminating. It
     *    bypasses for a mechanical reason rather than an urgency one: its cadence
     *    (`GRACE_POLL_INTERVAL_MS`) is *exactly* [MIN_UPDATE_INTERVAL_SECONDS], so leaving it
     *    floored would drop roughly every other tick to timing jitter and silently halve the poll
     *    rate.
     *
     * Everything else — startup, config-change, the `E+30s` wake, on-enter — goes through the
     * floor. That is what stops several triggers coinciding (a config change and a timer, say)
     * from each costing a fetch.
     */
    /**
     * Whether THIS process has asked for a status fetch yet. Deliberately in-memory and deliberately
     * not the same thing as the persisted timestamp: see [shouldFetch].
     *
     * ⚠️ **LOAD-BEARING — do not delete this as redundant with the persisted timestamp.** It looks
     * redundant, and it is not: the persisted value answers *"was the last fetch recent?"*, this
     * answers *"has this process asked at all?"*.
     *
     * Its job is to guarantee the FIRST request of a process reaches the network. Precisely: **this is
     * what stops the floor becoming a mutex.**
     *
     * Three separate things can refuse to start a status fetch, and they are easy to conflate:
     *
     *  1. **in flight** — a request is already running,
     *  2. **unconfirmed** — we have no confirmed status (now [LoadState.Loaded.confirmedInThisProcess]),
     *  3. **too soon** — this floor.
     *
     * Only (3) is **persisted**, so it is the only one that can refuse in a process that has never
     * fetched at all: it reads a timestamp that outlived the process that wrote it. That is the same
     * shape as the `Loaded(stale)` bug — durable state answering a per-process question — arriving
     * through a different mechanism. Without this exemption, a relaunch inside the interval would be
     * refused by a decision no part of this process ever took, and after the startup gate nothing
     * else would ask.
     *
     * Note that (2) being handled properly by [LoadState.Loaded.confirmedInThisProcess] does **not**
     * make this removable — the two guard different refusals, and only this one is reachable in a
     * process that has not fetched.
     *
     * What deleting it costs, concretely: the Pro settings screen refreshes on entry through the
     * floored path, so on a relaunch inside the interval that refresh is refused, the screen has no
     * confirmed status to render, and nothing remaining will ask. It spins until the interval expires.
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
         * The status freshness floor. **Shared cross-client contract** — Desktop and iOS use the
         * same 60s (`SessionPro.StatusRefresh.floorSeconds`, `STATUS_FLOOR_MS`); keep them in step,
         * and say why in the commit if they ever have to diverge.
         *
         * ⚠️ **A scheduled wake depends on this not being crossed.** `ProStatusManager`'s
         * `user_expiry` trigger arms two wakes — at the renewal date and at coverage end — and both
         * reach the network through the FLOORED path here, not through `immediate`. When the grace
         * period is shorter than this floor the two wakes land inside it and **the second one's fetch
         * is dropped.**
         *
         * In production that cannot happen: grace is at least the ~1h latency allowance, and an
         * operator-configured value in days once a subscriber is actually in grace. It happens on
         * **compressed QA backends**, which set grace to seconds so the window can be exercised in a
         * test run.
         *
         * Deliberately not worked around here (ruled 2026-08-10). If UI-test work needs to exercise
         * the coverage-end wake, the sanctioned escape hatch is an **env-var override of this
         * constant**, owned by that work — not an `immediate` fetch for a scheduled trigger, which
         * would reopen exactly what `force` -> `immediate` closed.
         */
        const val MIN_UPDATE_INTERVAL_SECONDS = 60L

        /**
         * The whole of the floor decision, over plain values so it can be tested without a
         * database, a clock or WorkManager.
         *
         * Expressed over the **timestamp**, never over a load-state enum. That distinction is the
         * bug this replaced: the old check asked whether the in-memory state was `Loading`/`Loaded`,
         * and the state a cold start begins in — `Init` — is neither, so the floor was skipped
         * outright on exactly the path it exists to cover. An absent timestamp means "no successful
         * fetch on record", which is a genuine reason to fetch; an initial enum value is not.
         *
         * Drop-on-fresh, not re-arm (spec §4): a caller whose request is dropped here does not get
         * a later one scheduled on its behalf. The proof loop is deliberately the opposite.
         *
         * [fetchedInThisProcess] is the second exemption, and it needs the persisted timestamp to
         * exist before it makes sense — the two are not redundant. A process that has never fetched
         * always fetches once, however fresh the stored value is, because several things downstream
         * key off *this process* having confirmed the status rather than off the status being
         * recent. On Android that is the false-expired protection: the home Expired CTA is gated on
         * `refreshState is State.Success` (`HomeViewModel`), and `Init` maps to `Success` — so what
         * actually suppressed the CTA until confirmation was the `Loading` transition a real fetch
         * produces. Floor the very first request of a process and that transition never happens,
         * and the CTA fires off whatever the cache last held. Cold-start load stays bounded by the
         * 24h startup gate, which is the stronger limit anyway.
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
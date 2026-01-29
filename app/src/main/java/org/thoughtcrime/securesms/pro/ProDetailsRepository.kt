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
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.pro.api.ProDetails
import org.thoughtcrime.securesms.pro.db.ProDatabase
import org.thoughtcrime.securesms.util.NetworkConnectivity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProDetailsRepository @Inject constructor(
    private val application: Application,
    private val db: ProDatabase,
    private val snodeClock: SnodeClock,
    @ManagerScope scope: CoroutineScope,
    loginStateRepository: LoginStateRepository,
    private val prefs: TextSecurePreferences,
    private val networkConnectivity: NetworkConnectivity,
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


    val loadState: StateFlow<LoadState> = loginStateRepository.flowWithLoggedInState {
        combine(
            FetchProDetailsWorker.watch(application)
                .map { it.state }
                .distinctUntilChanged(),

            networkConnectivity.networkAvailable,

            db.proDetailsChangeNotification
                .onStart { emit(Unit) }
                .map { db.getProDetailsAndLastUpdated() }
        ) { state, isOnline, last ->
            when (state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> LoadState.Loading(last, waitingForNetwork = !isOnline)
                WorkInfo.State.RUNNING -> LoadState.Loading(last, waitingForNetwork = false)
                WorkInfo.State.SUCCEEDED -> {
                    if (last != null) {
                        Log.d(DebugLogGroup.PRO_DATA.label, "Successfully fetched Pro details from backend")
                        LoadState.Loaded(last)
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
     * Requests a fresh of current user's pro details. By default, if last update is recent enough,
     * no network request will be made. If [force] is true, a network request will be
     * made regardless of the freshness of the last update.
     */
    fun requestRefresh(force: Boolean = false) {
        if (!prefs.forcePostPro()) {
            Log.d(DebugLogGroup.PRO_DATA.label, "Pro hasn't been enabled, skipping refresh")
            return
        }

        val currentState = loadState.value
        if (!force && (currentState is LoadState.Loading || currentState is LoadState.Loaded) &&
            currentState.lastUpdated?.second?.plusSeconds(MIN_UPDATE_INTERVAL_SECONDS)
                ?.isAfter(snodeClock.currentTime()) == true) {
            Log.d(DebugLogGroup.PRO_DATA.label, "Pro details are fresh enough, skipping refresh")
            return
        }

        Log.d(DebugLogGroup.PRO_DATA.label, "Scheduling fetch of Pro details from server")
        FetchProDetailsWorker.schedule(application, ExistingWorkPolicy.KEEP)
    }


    companion object {
        private const val MIN_UPDATE_INTERVAL_SECONDS = 60L
    }
}
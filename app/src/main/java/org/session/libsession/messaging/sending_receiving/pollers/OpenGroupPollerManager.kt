package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.util.castAwayType
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

private const val TAG = "OpenGroupPollerManager"

/**
 * [OpenGroupPollerManager] manages the lifecycle of [OpenGroupPoller] instances for all
 * subscribed open groups. It creates a poller for a server (a server can host
 * multiple open groups), and it stops the poller when the server is no longer subscribed by
 * any open groups.
 *
 * This process is fully responsive to changes in the user's config and as long as the config
 * is up to date, the pollers will be created and stopped correctly.
 */
@Singleton
class OpenGroupPollerManager @Inject constructor(
    pollerFactory: OpenGroupPoller.Factory,
    configFactory: ConfigFactoryProtocol,
    loginStateRepository: LoginStateRepository,
    @ManagerScope scope: CoroutineScope
) : OnAppStartupComponent {
    private val pollerSemaphore = Semaphore(3)

    val pollers: StateFlow<Map<String, OpenGroupPoller>> =
        loginStateRepository.flowWithLoggedInState {
            configFactory
                .userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.USER_GROUPS))
                .castAwayType()
                .onStart { emit(Unit) }
                .map {
                    configFactory.withUserConfigs { configs ->
                        configs.userGroups.allCommunityInfo()
                    }.mapTo(hashSetOf()) { it.community.baseUrl }
                }
        }
            .distinctUntilChanged()
            .scan(emptyMap<String, OpenGroupPoller>()) { acc, value ->
                if (acc.keys == value) {
                    acc // No change, return the same map
                } else {
                    val newPollerStates = value.associateWith { baseUrl ->
                        acc[baseUrl] ?: run {
                            Log.d(TAG, "Creating new poller for $baseUrl")
                            pollerFactory.create(baseUrl, pollerSemaphore)
                        }
                    }

                    for ((baseUrl, poller) in acc) {
                        if (baseUrl !in value) {
                            Log.d(TAG, "Stopping poller for $baseUrl")
                            poller.cancel()
                        }
                    }

                    newPollerStates
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            while (true) {
                pollers.value.forEach { (url, poller) ->
                    Log.d(TAG, "OpenGroupPoller($url), jobStatus = ${poller.mainJobStatus}, pollState = ${poller.pollState.value}")
                }
            }

            delay(10.seconds)
        }
    }

    val isAllCaughtUp: Boolean
        get() = pollers.value.values.all {
            it.pollState.value is BasePoller.PollState.Polled
        }


    suspend fun pollAllOpenGroupsOnce() {
        Log.d(TAG, "Polling all open groups once")
        supervisorScope {
            pollers.value.map { (_, poller) ->
                async {
                    poller.manualPollOnce()
                }
            }.awaitAll()
        }
    }
}
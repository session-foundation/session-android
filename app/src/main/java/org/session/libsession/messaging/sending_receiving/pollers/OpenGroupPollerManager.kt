package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import javax.inject.Inject
import javax.inject.Singleton

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
    preferences: TextSecurePreferences,
    @ManagerScope scope: CoroutineScope
) : OnAppStartupComponent {
    val pollers: StateFlow<Map<String, PollerHandle>> =
        preferences.watchLocalNumber()
            .map { it != null }
            .distinctUntilChanged()
            .flatMapLatest { loggedIn ->
                if (loggedIn) {
                    (configFactory
                        .configUpdateNotifications
                        .filter { it is ConfigUpdateNotification.UserConfigsMerged || it == ConfigUpdateNotification.UserConfigsModified } as Flow<*>)
                        .onStart { emit(Unit) }
                        .map {
                            configFactory.withUserConfigs { configs ->
                                configs.userGroups.allCommunityInfo()
                            }.mapTo(hashSetOf()) { it.community.baseUrl }
                        }
                } else {
                    flowOf(emptySet())
                }
            }
            .scan(emptyMap<String, PollerHandle>()) { acc, value ->
                if (acc.keys == value) {
                    acc // No change, return the same map
                } else {
                    val newPollerStates = value.associateWith { baseUrl ->
                        acc[baseUrl] ?: run {
                            val scope = CoroutineScope(Dispatchers.Default)
                            Log.d(TAG, "Creating new poller for $baseUrl")
                            PollerHandle(
                                poller = pollerFactory.create(baseUrl, scope),
                                pollerScope = scope
                            )
                        }
                    }

                    for ((baseUrl, handle) in acc) {
                        if (baseUrl !in value) {
                            Log.d(TAG, "Stopping poller for $baseUrl")
                            handle.pollerScope.cancel()
                        }
                    }

                    newPollerStates
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val isAllCaughtUp: Boolean
        get() = pollers.value.values.all { it.poller.isCaughtUp.value }


    suspend fun pollAllOpenGroupsOnce() {
        Log.d(TAG, "Polling all open groups once")
        supervisorScope {
            pollers.value.map { (server, handle) ->
                handle.pollerScope.launch {
                    runCatching {
                        handle.poller.requestPollOnceAndWait()
                    }.onFailure {
                        Log.e(TAG, "Error polling open group ${server}", it)
                    }
                }
            }.joinAll()
        }
    }

    data class PollerHandle(
        val poller: OpenGroupPoller,
        val pollerScope: CoroutineScope
    )
}
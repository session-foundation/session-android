package org.thoughtcrime.securesms.groups

import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.supervisorScope
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.InternetConnectivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This class manages the lifecycle of group pollers.
 *
 * It listens for changes in the user's groups config and create or destroyed pollers as needed. Other
 * factors like network availability and user's local number are also taken into account.
 *
 * All the processes here are automatic and you don't need to do anything to create or destroy pollers.
 *
 * This class also provide state monitoring facilities to check the state of a group poller.
 *
 * Note that whether a [GroupPoller] is polling things or not is determined by itself. The manager
 * class is only responsible for the overall lifecycle of the pollers.
 */
@Singleton
class GroupPollerManager @Inject constructor(
    configFactory: ConfigFactory,
    lokiApiDatabase: LokiAPIDatabaseProtocol,
    clock: SnodeClock,
    preferences: TextSecurePreferences,
    appVisibilityManager: AppVisibilityManager,
    connectivity: InternetConnectivity,
    groupRevokedMessageHandler: GroupRevokedMessageHandler,
) {
    @Suppress("OPT_IN_USAGE")
    private val groupPollers: StateFlow<Map<AccountId, GroupPollerHandle>> =
        combine(
            connectivity.networkAvailable,
            preferences.watchLocalNumber()
        ) { networkAvailable, localNumber -> networkAvailable && localNumber != null }
            // This flatMap produces a flow of groups that should be polled now
            .flatMapLatest { shouldPoll ->
                if (shouldPoll) {
                    (configFactory.configUpdateNotifications
                        .filter { it is ConfigUpdateNotification.UserConfigsMerged || it == ConfigUpdateNotification.UserConfigsModified } as Flow<*>)
                        .onStart { emit(Unit) }
                        .map {
                            configFactory.withUserConfigs { it.userGroups.allClosedGroupInfo() }
                                .mapNotNullTo(hashSetOf()) { group ->
                                    group.groupAccountId.takeIf { group.shouldPoll }
                                }
                        }
                        .distinctUntilChanged()
                } else {
                    // There shouldn't be any group polling at this stage
                    flowOf(emptySet())
                }
            }

            // This scan compares the previous active group pollers with the incoming set of groups
            // that should be polled now, to work out which pollers should be started or stopped,
            // and finally emits the new state
            .scan(emptyMap<AccountId, GroupPollerHandle>()) { previous, newActiveGroupIDs ->
                // Go through previous pollers and stop those that are not in the new set
                for ((groupId, poller) in previous) {
                    if (groupId !in newActiveGroupIDs) {
                        Log.d(TAG, "Stopping poller for $groupId")
                        poller.scope.cancel()
                    }
                }

                // Go through new set, pick the existing pollers and create/start those that are
                // not in the previous map
                newActiveGroupIDs.associateWith { groupId ->
                    var poller = previous[groupId]

                    if (poller == null) {
                        Log.d(TAG, "Starting poller for $groupId")
                        val scope = CoroutineScope(Dispatchers.Default)
                        poller = GroupPollerHandle(
                            poller = GroupPoller(
                                scope = scope,
                                groupId = groupId,
                                configFactoryProtocol = configFactory,
                                lokiApiDatabase = lokiApiDatabase,
                                clock = clock,
                                appVisibilityManager = appVisibilityManager,
                                groupRevokedMessageHandler = groupRevokedMessageHandler,
                            ),
                            scope = scope
                        )
                    }

                    poller
                }
            }

            .stateIn(GlobalScope, SharingStarted.Eagerly, emptyMap())


    @Suppress("OPT_IN_USAGE")
    fun watchGroupPollingState(groupId: AccountId): Flow<GroupPoller.State> {
        return groupPollers
            .flatMapLatest { pollers ->
                pollers[groupId]?.poller?.state ?: flowOf(GroupPoller.State())
            }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchAllGroupPollingState(): Flow<Pair<AccountId, GroupPoller.State>> {
        return groupPollers
            .flatMapLatest { pollers ->
                // Merge all poller states into a single flow of (groupId, state) pairs
                merge(
                    *pollers
                        .map { (id, poller) -> poller.poller.state.map { state -> id to state } }
                        .toTypedArray()
                )
            }
    }

    suspend fun pollAllGroupsOnce() {
        supervisorScope {
            groupPollers.value.values.map {
                async {
                    it.poller.requestPollOnce()
                }
            }.awaitAll()
        }
    }

    /**
     * Wait for a group to be polled once and return the poll result
     *
     * Note that if the group is not supposed to be polled (kicked, destroyed, etc) then
     * this function will hang forever. It's your responsibility to set a timeout if needed.
     */
    suspend fun pollOnce(groupId: AccountId): GroupPoller.PollResult {
        return groupPollers.mapNotNull { it[groupId] }
            .first()
            .poller
            .requestPollOnce()
    }

    data class GroupPollerHandle(
        val poller: GroupPoller,
        val scope: CoroutineScope,
    )

    companion object {
        private const val TAG = "GroupPollerHandler"
    }
}

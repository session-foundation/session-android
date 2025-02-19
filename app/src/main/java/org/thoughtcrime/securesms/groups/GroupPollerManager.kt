package org.thoughtcrime.securesms.groups

import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.sending_receiving.pollers.ClosedGroupPoller
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.POLLER_SCOPE
import org.thoughtcrime.securesms.util.AppVisibilityManager
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * This class manages the lifecycle of group pollers.
 *
 * It listens for changes in the user's groups config and starts or stops pollers as needed. It
 * also considers the app's visibility state to decide whether to start or stop pollers.
 *
 * All the processes here are automatic and you don't need to do anything to start or stop pollers.
 *
 * This class also provide state monitoring facilities to check the state of a group poller.
 */
@Singleton
class GroupPollerManager @Inject constructor(
    @Named(POLLER_SCOPE) scope: CoroutineScope,
    @Named(POLLER_SCOPE) executor: CoroutineDispatcher,
    configFactory: ConfigFactory,
    groupManagerV2: Lazy<GroupManagerV2>,
    storage: StorageProtocol,
    lokiApiDatabase: LokiAPIDatabaseProtocol,
    clock: SnodeClock,
    preferences: TextSecurePreferences,
    appVisibilityManager: AppVisibilityManager,
) {
    @Suppress("OPT_IN_USAGE")
    private val activeGroupPollers: StateFlow<Map<AccountId, ClosedGroupPoller>> =
        combine(
            preferences.watchLocalNumber(),
            appVisibilityManager.isAppVisible
        ) { localNumber, visible -> localNumber != null && visible }
            .distinctUntilChanged()

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
            .scan(emptyMap<AccountId, ClosedGroupPoller>()) { previous, newActiveGroupIDs ->
                // Go through previous pollers and stop those that are not in the new set
                for ((groupId, poller) in previous) {
                    if (groupId !in newActiveGroupIDs) {
                        Log.d(TAG, "Stopping poller for $groupId")
                        poller.stop()
                    }
                }

                // Go through new set, pick the existing pollers and create/start those that are
                // not in the previous map
                newActiveGroupIDs.associateWith { groupId ->
                    var poller = previous[groupId]

                    if (poller == null) {
                        Log.d(TAG, "Starting poller for $groupId")
                        poller = ClosedGroupPoller(
                            scope = scope,
                            executor = executor,
                            closedGroupSessionId = groupId,
                            configFactoryProtocol = configFactory,
                            groupManagerV2 = groupManagerV2.get(),
                            storage = storage,
                            lokiApiDatabase = lokiApiDatabase,
                            clock = clock,
                        ).also { it.start() }
                    }

                    poller
                }
            }

            .stateIn(GlobalScope, SharingStarted.Eagerly, emptyMap())


    @Suppress("OPT_IN_USAGE")
    fun watchGroupPollingState(groupId: AccountId): Flow<ClosedGroupPoller.State> {
        return activeGroupPollers
            .flatMapLatest { pollers ->
                pollers[groupId]?.state ?: flowOf(ClosedGroupPoller.IdleState)
            }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchAllGroupPollingState(): Flow<Pair<AccountId, ClosedGroupPoller.State>> {
        return activeGroupPollers
            .flatMapLatest { pollers ->
                // Merge all poller states into a single flow of (groupId, state) pairs
                merge(
                    *pollers
                    .map { (id, poller) -> poller.state.map { state -> id to state } }
                    .toTypedArray()
                )
            }
    }

    companion object {
        private const val TAG = "GroupPollerHandler"
    }
}

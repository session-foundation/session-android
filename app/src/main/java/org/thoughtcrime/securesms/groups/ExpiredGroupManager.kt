package org.thoughtcrime.securesms.groups

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This class keeps track of what groups are expired.
 *
 * This is done by listening to the state of all group pollers and keeping track of last
 * known expired state of each group.
 *
 * The end result is not persisted and is only available in memory.
 */
@Singleton
class ExpiredGroupManager @Inject constructor(
    pollerManager: GroupPollerManager,
) {
    @Suppress("OPT_IN_USAGE")
    val expiredGroups: StateFlow<Set<AccountId>> = pollerManager.watchAllGroupPollingState()
        .mapNotNull { (groupId, state) ->
            val expired = state.lastPoll?.groupExpired

            if (expired == null) {
                // Poller doesn't know about the expiration state yet, so we skip
                // the this update. It's important we do this as we want to use the
                // "last known state" if the poller doesn't know about the expiration state yet.
                return@mapNotNull null
            }

            groupId to expired
        }

        // This scan keep track of all expired groups. Whenever there is a new state for a group
        // poller, we compare the state with the previous state and update the set of expired groups.
        .scan(emptySet<AccountId>()) { previous, (groupId, expired) ->
            if (expired && groupId !in previous) {
                Log.d("ExpiredGroupManager", "Marking group $groupId expired.")
                previous + groupId
            } else if (!expired && groupId in previous) {
                Log.d("ExpiredGroupManager", "Unmarking group $groupId expired.")
                previous - groupId
            } else {
                previous
            }
        }

        .stateIn(GlobalScope, SharingStarted.Eagerly, emptySet())
}
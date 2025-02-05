package org.thoughtcrime.securesms.groups.handler

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.waitUntilGroupConfigsPushed
import org.session.libsignal.utilities.Log
import org.session.libsession.messaging.groups.GroupScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A handler that listens for group config updates, check the "destroyed" flag in the GroupInfoConfig
 * and updates the UserGroupsConfig accordingly.
 */
@Singleton
class DestroyedGroupSync @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val groupScope: GroupScope,
    private val storage: StorageProtocol,
) {
    private var job: Job? = null

    fun start() {
        require(job == null) { "Already started" }

        job = GlobalScope.launch {
            configFactory.configUpdateNotifications
                .filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
                .collect { update ->
                    val isDestroyed = configFactory.withGroupConfigs(update.groupId) {
                        it.groupInfo.isDestroyed()
                    }

                    Log.d("DestroyedGroupSync", "Group is destroyed: $isDestroyed")

                    if (isDestroyed) {
                        groupScope.launch(update.groupId, "DestroyedGroupSync") {
                            // If there's un-pushed group config updates, wait until they are pushed.
                            // This is important, as the pushing process might need to access the UserGroupConfig,
                            // if we delete the UserGroupConfig before the pushing process, the pushing
                            // process will fail.
                            configFactory.waitUntilGroupConfigsPushed(update.groupId)

                            configFactory.withMutableUserConfigs { configs ->
                                configs.userGroups.getClosedGroup(update.groupId.hexString)?.let { group ->
                                    configs.userGroups.set(group.copy(destroyed = true))
                                }
                            }
                        }

                        // Also clear all messages in the group
                        storage.getThreadId(Address.fromSerialized(update.groupId.hexString))?.let { threadId ->
                            storage.clearMessages(threadId)
                        }
                    }
                }
        }
    }
}
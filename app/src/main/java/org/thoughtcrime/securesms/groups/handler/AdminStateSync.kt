package org.thoughtcrime.securesms.groups.handler

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getGroup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A handler that listens for group config updates, and make sure the our group member admin state
 * is in sync with the UserGroupConfig.
 *
 * This concerns the "admin", "promotionStatus" in the GroupMemberConfig
 *
 */
@Singleton
class AdminStateSync @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val preferences: TextSecurePreferences,
) {
    private var job: Job? = null

    fun start() {
        require(job == null) { "Already started" }

        job = GlobalScope.launch {
            configFactory.configUpdateNotifications
                .filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
                .collect { update ->
                    val localNumber = preferences.getLocalNumber() ?: return@collect
                    val isAdmin = configFactory.getGroup(update.groupId)?.hasAdminKey() == true

                    if (isAdmin) {
                        // If the UserGroupConfig says we are admin, we'll set the group member
                        // config's promotion status to "accepted" mark ourselves admin regardless.
                        configFactory.withMutableGroupConfigs(update.groupId) { groupConfigs ->
                            groupConfigs.groupMembers.get(localNumber)?.let { me ->
                                me.setPromotionAccepted()
                                groupConfigs.groupMembers.set(me)
                            }
                        }
                    } else {
                        // You can't really change the group config if you are not admin so the reverse
                        // logic doesn't need to be done.
                    }
                }
        }
    }
}
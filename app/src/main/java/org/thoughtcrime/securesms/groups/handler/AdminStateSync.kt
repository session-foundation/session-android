package org.thoughtcrime.securesms.groups.handler

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getGroup
import org.session.libsignal.utilities.AccountId
import java.util.EnumSet
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
                .filter { it is ConfigUpdateNotification.UserConfigsMerged || it == ConfigUpdateNotification.UserConfigsModified }
                .collect {
                    val localNumber = preferences.getLocalNumber() ?: return@collect

                    // Go through evey user groups and if we are admin of any of the groups,
                    // make sure we mark any pending group promotion status as "accepted"

                    val allAdminGroups = configFactory.withUserConfigs { configs ->
                        configs.userGroups.all()
                            .asSequence()
                            .mapNotNull {
                                if ((it as? GroupInfo.ClosedGroupInfo)?.hasAdminKey() == true) {
                                    it.groupAccountId
                                } else {
                                    null
                                }
                            }
                    }

                    val groupToMarkAccepted = allAdminGroups
                        .filter { groupId -> isMemberPromotionPending(groupId, localNumber) }

                    for (groupId in groupToMarkAccepted) {
                        configFactory.withMutableGroupConfigs(groupId) { groupConfigs ->
                            groupConfigs.groupMembers.get(localNumber)?.let { member ->
                                member.setPromotionAccepted()
                                groupConfigs.groupMembers.set(member)
                            }
                        }
                    }
                }
        }
    }

    private fun isMemberPromotionPending(groupId: AccountId, localNumber: String): Boolean {
        return configFactory.withGroupConfigs(groupId) { groupConfigs ->
            val status = groupConfigs.groupMembers.get(localNumber)?.status
            status != null && status in EnumSet.of(
                GroupMember.Status.PROMOTION_SENT,
                GroupMember.Status.PROMOTION_FAILED,
                GroupMember.Status.PROMOTION_NOT_SENT
            )
        }
    }
}
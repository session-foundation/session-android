package org.thoughtcrime.securesms.groups.handler

import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsession.utilities.withMutableGroupConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
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
) : AuthAwareComponent {
    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        configFactory.userConfigsChanged(onlyConfigTypes = setOf(UserConfigType.USER_GROUPS))
            .collect {
                val localNumber = loggedInState.accountId.hexString

                // Go through evey user groups and if we are admin of any of the groups,
                // make sure we mark any pending group promotion status as "accepted"

                val allAdminGroups = configFactory.withUserConfigs { configs ->
                    configs.userGroups.all()
                        .asSequence()
                        .mapNotNull {
                            if ((it as? GroupInfo.ClosedGroupInfo)?.hasAdminKey() == true) {
                                AccountId(it.groupAccountId)
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

    private fun isMemberPromotionPending(groupId: AccountId, localNumber: String): Boolean {
        return configFactory.withGroupConfigs(groupId) { groupConfigs ->
            val status = groupConfigs.groupMembers.get(localNumber)?.let(groupConfigs.groupMembers::status)
            status != null && status in EnumSet.of(
                GroupMember.Status.PROMOTION_SENT,
                GroupMember.Status.PROMOTION_FAILED,
                GroupMember.Status.PROMOTION_NOT_SENT
            )
        }
    }
}
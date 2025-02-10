package org.thoughtcrime.securesms.groups.handler

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.allWithStatus
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.messaging.groups.GroupScope
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import javax.inject.Inject

/**
 * This handler is responsible for cleaning up the intermediate states that are created when
 * sending invitations to join a group. This clean-up is necessary because the app could crash
 * or being killed before the invitation is sent, and we don't want to leave the group member in a
 * "pending" state while it's not really pending.
 *
 * This is achieved by checking the group members and marking the "pending" state as "failed"
 * after the app is started, and only done once on every app process.
 */
class CleanupInvitationHandler @Inject constructor(
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactoryProtocol,
    private val groupScope: GroupScope
) {
    fun start() {
        GlobalScope.launch {
            // Wait for the local number to be available
            prefs.watchLocalNumber().first { it != null }

            val allGroups = configFactory.withUserConfigs {
                it.userGroups.allClosedGroupInfo()
            }

            allGroups
                .asSequence()
                .filter { !it.kicked && !it.destroyed && it.hasAdminKey() }
                .forEach { group ->
                    groupScope.launch(group.groupAccountId, debugName = "CleanupInvitationHandler") {
                        configFactory.withMutableGroupConfigs(group.groupAccountId) { configs ->
                            configs.groupMembers
                                .allWithStatus()
                                .filter { it.second == GroupMember.Status.INVITE_SENDING }
                                .forEach { (member, _) ->
                                    member.setInviteFailed()
                                    configs.groupMembers.set(member)
                                }
                        }
                    }
                }
        }
    }
}
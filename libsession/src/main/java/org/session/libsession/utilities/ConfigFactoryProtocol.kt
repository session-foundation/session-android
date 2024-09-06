package org.session.libsession.utilities

import kotlinx.coroutines.flow.Flow
import network.loki.messenger.libsession_util.Config
import network.loki.messenger.libsession_util.ConfigBase
import network.loki.messenger.libsession_util.Contacts
import network.loki.messenger.libsession_util.ConversationVolatileConfig
import network.loki.messenger.libsession_util.GroupInfoConfig
import network.loki.messenger.libsession_util.GroupKeysConfig
import network.loki.messenger.libsession_util.GroupMembersConfig
import network.loki.messenger.libsession_util.UserGroupsConfig
import network.loki.messenger.libsession_util.UserProfile
import org.session.libsession.messaging.messages.Destination
import org.session.libsignal.utilities.AccountId

interface ConfigFactoryProtocol {

    val user: UserProfile?
    val contacts: Contacts?
    val convoVolatile: ConversationVolatileConfig?
    val userGroups: UserGroupsConfig?

    val configUpdateNotifications: Flow<Unit>

    fun getGroupInfoConfig(groupSessionId: AccountId): GroupInfoConfig?
    fun getGroupMemberConfig(groupSessionId: AccountId): GroupMembersConfig?
    fun getGroupKeysConfig(groupSessionId: AccountId,
                           info: GroupInfoConfig? = null,
                           members: GroupMembersConfig? = null,
                           free: Boolean = true): GroupKeysConfig?

    fun getUserConfigs(): List<ConfigBase>
    fun persist(forConfigObject: Config, timestamp: Long, forPublicKey: String? = null)

    fun conversationInConfig(publicKey: String?, groupPublicKey: String?, openGroupId: String?, visibleOnly: Boolean): Boolean
    fun canPerformChange(variant: String, publicKey: String, changeTimestampMs: Long): Boolean
    fun saveGroupConfigs(
        groupKeys: GroupKeysConfig,
        groupInfo: GroupInfoConfig,
        groupMembers: GroupMembersConfig
    )
    fun removeGroup(closedGroupId: AccountId)

    fun scheduleUpdate(destination: Destination)
    fun constructGroupKeysConfig(
        groupSessionId: AccountId,
        info: GroupInfoConfig,
        members: GroupMembersConfig
    ): GroupKeysConfig?

    fun maybeDecryptForUser(encoded: ByteArray,
                            domain: String,
                            closedGroupSessionId: AccountId): ByteArray?

    fun userSessionId(): AccountId?

}

interface ConfigFactoryUpdateListener {
    fun notifyUpdates(forConfigObject: Config, messageTimestamp: Long)
}

/**
 * Access group configs if they exist, otherwise return null.
 *
 * Note: The config objects will be closed after the callback is executed. Any attempt
 * to store the config objects will result in a native crash.
 */
inline fun <T: Any> ConfigFactoryProtocol.withGroupConfigsOrNull(
    groupId: AccountId,
    cb: (GroupInfoConfig, GroupMembersConfig, GroupKeysConfig) -> T
): T? {
    getGroupInfoConfig(groupId)?.use { groupInfo ->
        getGroupMemberConfig(groupId)?.use { groupMembers ->
            getGroupKeysConfig(groupId, groupInfo, groupMembers)?.use { groupKeys ->
                return cb(groupInfo, groupMembers, groupKeys)
            }
        }
    }

    return null
}
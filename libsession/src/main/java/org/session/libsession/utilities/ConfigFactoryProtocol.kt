package org.session.libsession.utilities

import kotlinx.coroutines.flow.Flow
import network.loki.messenger.libsession_util.MutableConfig
import network.loki.messenger.libsession_util.MutableContacts
import network.loki.messenger.libsession_util.MutableConversationVolatileConfig
import network.loki.messenger.libsession_util.MutableGroupInfoConfig
import network.loki.messenger.libsession_util.MutableGroupKeysConfig
import network.loki.messenger.libsession_util.MutableGroupMembersConfig
import network.loki.messenger.libsession_util.MutableUserGroupsConfig
import network.loki.messenger.libsession_util.MutableUserProfile
import network.loki.messenger.libsession_util.ReadableConfig
import network.loki.messenger.libsession_util.ReadableContacts
import network.loki.messenger.libsession_util.ReadableConversationVolatileConfig
import network.loki.messenger.libsession_util.ReadableGroupInfoConfig
import network.loki.messenger.libsession_util.ReadableGroupKeysConfig
import network.loki.messenger.libsession_util.ReadableGroupMembersConfig
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.ReadableUserProfile
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.AccountId

interface ConfigFactoryProtocol {
    val configUpdateNotifications: Flow<ConfigUpdateNotification>

    fun <T> withUserConfigs(cb: (UserConfigs) -> T): T
    fun <T> withMutableUserConfigs(cb: (MutableUserConfigs) -> T): T

    fun <T> withGroupConfigs(groupId: AccountId, cb: (GroupConfigs) -> T): T
    fun <T> withMutableGroupConfigs(groupId: AccountId, cb: (MutableGroupConfigs) -> T): T

    fun conversationInConfig(publicKey: String?, groupPublicKey: String?, openGroupId: String?, visibleOnly: Boolean): Boolean
    fun canPerformChange(variant: String, publicKey: String, changeTimestampMs: Long): Boolean

    fun getGroupAuth(groupId: AccountId): SwarmAuth?
    fun removeGroup(groupId: AccountId)

    fun maybeDecryptForUser(encoded: ByteArray,
                            domain: String,
                            closedGroupSessionId: AccountId): ByteArray?

}


interface UserConfigs {
    val contacts: ReadableContacts
    val userGroups: ReadableUserGroupsConfig
    val userProfile: ReadableUserProfile
    val convoInfoVolatile: ReadableConversationVolatileConfig

    fun allConfigs(): Sequence<ReadableConfig> = sequenceOf(contacts, userGroups, userProfile, convoInfoVolatile)
}

interface MutableUserConfigs : UserConfigs {
    override val contacts: MutableContacts
    override val userGroups: MutableUserGroupsConfig
    override val userProfile: MutableUserProfile
    override val convoInfoVolatile: MutableConversationVolatileConfig

    override fun allConfigs(): Sequence<MutableConfig> = sequenceOf(contacts, userGroups, userProfile, convoInfoVolatile)
}

interface GroupConfigs {
    val groupInfo: ReadableGroupInfoConfig
    val groupMembers: ReadableGroupMembersConfig
    val groupKeys: ReadableGroupKeysConfig
}

interface MutableGroupConfigs : GroupConfigs {
    override val groupInfo: MutableGroupInfoConfig
    override val groupMembers: MutableGroupMembersConfig
    override val groupKeys: MutableGroupKeysConfig

    fun loadKeys(message: ByteArray, hash: String, timestamp: Long): Boolean
    fun rekeys()
}

sealed interface ConfigUpdateNotification {
    data object UserConfigs : ConfigUpdateNotification
    data class GroupConfigsUpdated(val groupId: AccountId) : ConfigUpdateNotification
    data class GroupConfigsDeleted(val groupId: AccountId) : ConfigUpdateNotification
}

//interface ConfigFactoryUpdateListener {
//    fun notifyUpdates(forConfigObject: Config, messageTimestamp: Long)
//}



///**
// * Access group configs if they exist, otherwise return null.
// *
// * Note: The config objects will be closed after the callback is executed. Any attempt
// * to store the config objects will result in a native crash.
// */
//inline fun <T: Any> ConfigFactoryProtocol.withGroupConfigsOrNull(
//    groupId: AccountId,
//    cb: (GroupInfoConfig, GroupMembersConfig, GroupKeysConfig) -> T
//): T? {
//    getGroupInfoConfig(groupId)?.use { groupInfo ->
//        getGroupMemberConfig(groupId)?.use { groupMembers ->
//            getGroupKeysConfig(groupId, groupInfo, groupMembers)?.use { groupKeys ->
//                return cb(groupInfo, groupMembers, groupKeys)
//            }
//        }
//    }
//
//    return null
//}
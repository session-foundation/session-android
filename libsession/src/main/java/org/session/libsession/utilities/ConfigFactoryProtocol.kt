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
import network.loki.messenger.libsession_util.util.ConfigPush
import network.loki.messenger.libsession_util.util.GroupInfo
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Namespace

interface ConfigFactoryProtocol {
    val configUpdateNotifications: Flow<ConfigUpdateNotification>

    fun <T> withUserConfigs(cb: (UserConfigs) -> T): T
    fun <T> withMutableUserConfigs(cb: (MutableUserConfigs) -> T): T
    fun mergeUserConfigs(userConfigType: UserConfigType, messages: List<ConfigMessage>)

    fun <T> withGroupConfigs(groupId: AccountId, cb: (GroupConfigs) -> T): T

    /**
     * @param recreateConfigInstances If true, the group configs will be recreated before calling the callback. This is useful when you have received an admin key or otherwise.
     */
    fun <T> withMutableGroupConfigs(groupId: AccountId, recreateConfigInstances: Boolean = false, cb: (MutableGroupConfigs) -> T): T

    fun conversationInConfig(publicKey: String?, groupPublicKey: String?, openGroupId: String?, visibleOnly: Boolean): Boolean
    fun canPerformChange(variant: String, publicKey: String, changeTimestampMs: Long): Boolean

    fun getGroupAuth(groupId: AccountId): SwarmAuth?
    fun removeGroup(groupId: AccountId)

    fun maybeDecryptForUser(encoded: ByteArray,
                            domain: String,
                            closedGroupSessionId: AccountId): ByteArray?

    fun mergeGroupConfigMessages(
        groupId: AccountId,
        keys: List<ConfigMessage>,
        info: List<ConfigMessage>,
        members: List<ConfigMessage>
    )

    fun confirmUserConfigsPushed(
        contacts: Pair<ConfigPush, ConfigPushResult>? = null,
        userProfile: Pair<ConfigPush, ConfigPushResult>? = null,
        convoInfoVolatile: Pair<ConfigPush, ConfigPushResult>? = null,
        userGroups: Pair<ConfigPush, ConfigPushResult>? = null
    )

    fun confirmGroupConfigsPushed(
        groupId: AccountId,
        members: Pair<ConfigPush, ConfigPushResult>?,
        info: Pair<ConfigPush, ConfigPushResult>?,
        keysPush: ConfigPushResult?
    )
}

class ConfigMessage(
    val hash: String,
    val data: ByteArray,
    val timestamp: Long
)

data class ConfigPushResult(
    val hash: String,
    val timestamp: Long
)

enum class UserConfigType(val namespace: Int) {
    CONTACTS(Namespace.CONTACTS()),
    USER_PROFILE(Namespace.USER_PROFILE()),
    CONVO_INFO_VOLATILE(Namespace.CONVO_INFO_VOLATILE()),
    USER_GROUPS(Namespace.GROUPS()),
}

/**
 * Shortcut to get the group info for a closed group. Equivalent to: `withUserConfigs { it.userGroups.getClosedGroup(groupId) }`
 */
fun ConfigFactoryProtocol.getClosedGroup(groupId: AccountId): GroupInfo.ClosedGroupInfo? {
    return withUserConfigs { it.userGroups.getClosedGroup(groupId.hexString) }
}

interface UserConfigs {
    val contacts: ReadableContacts
    val userGroups: ReadableUserGroupsConfig
    val userProfile: ReadableUserProfile
    val convoInfoVolatile: ReadableConversationVolatileConfig

    fun getConfig(type: UserConfigType): ReadableConfig {
        return when (type) {
            UserConfigType.CONTACTS -> contacts
            UserConfigType.USER_PROFILE -> userProfile
            UserConfigType.CONVO_INFO_VOLATILE -> convoInfoVolatile
            UserConfigType.USER_GROUPS -> userGroups
        }
    }
}

interface MutableUserConfigs : UserConfigs {
    override val contacts: MutableContacts
    override val userGroups: MutableUserGroupsConfig
    override val userProfile: MutableUserProfile
    override val convoInfoVolatile: MutableConversationVolatileConfig

    override fun getConfig(type: UserConfigType): MutableConfig {
        return when (type) {
            UserConfigType.CONTACTS -> contacts
            UserConfigType.USER_PROFILE -> userProfile
            UserConfigType.CONVO_INFO_VOLATILE -> convoInfoVolatile
            UserConfigType.USER_GROUPS -> userGroups
        }
    }
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

    fun rekey()
}

sealed interface ConfigUpdateNotification {
    data object UserConfigs : ConfigUpdateNotification
    data class GroupConfigsUpdated(val groupId: AccountId) : ConfigUpdateNotification
    data class GroupConfigsDeleted(val groupId: AccountId) : ConfigUpdateNotification
}

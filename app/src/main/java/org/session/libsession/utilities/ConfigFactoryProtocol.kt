package org.session.libsession.utilities

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import network.loki.messenger.libsession_util.MutableConfig
import network.loki.messenger.libsession_util.MutableContacts
import network.loki.messenger.libsession_util.MutableConversationVolatileConfig
import network.loki.messenger.libsession_util.MutableGroupInfoConfig
import network.loki.messenger.libsession_util.MutableGroupKeysConfig
import network.loki.messenger.libsession_util.MutableGroupMembersConfig
import network.loki.messenger.libsession_util.MutableUserGroupsConfig
import network.loki.messenger.libsession_util.MutableUserProfile
import network.loki.messenger.libsession_util.Namespace
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
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.AccountId

interface ConfigFactoryProtocol {
    val configUpdateNotifications: Flow<ConfigUpdateNotification>

    fun <T> withUserConfigs(cb: (UserConfigs) -> T): T
    fun <T> withMutableUserConfigs(cb: (MutableUserConfigs) -> T): T
    fun mergeUserConfigs(userConfigType: UserConfigType, messages: List<ConfigMessage>)

    fun <T> withGroupConfigs(groupId: AccountId, cb: (GroupConfigs) -> T): T

    /**
     * Create a new group config instance. Note this does not save the group configs to the database.
     */
    fun createGroupConfigs(groupId: AccountId, adminKey: ByteArray): MutableGroupConfigs

    /**
     * Save the group configs to the database and the factory.
     *
     * **Note:** This will overwrite the existing group configs. Normally you don't want to use
     * this function, instead use [withMutableGroupConfigs] to modify the group configs. This
     * function is only useful when you just created a new group and want to save the configs.
     */
    fun saveGroupConfigs(groupId: AccountId, groupConfigs: MutableGroupConfigs)

    /**
     * @param recreateConfigInstances If true, the group configs will be recreated before calling the callback. This is useful when you have received an admin key or otherwise.
     */
    fun <T> withMutableGroupConfigs(groupId: AccountId, cb: (MutableGroupConfigs) -> T): T

    fun canPerformChange(variant: String, publicKey: String, changeTimestampMs: Long): Boolean

    fun getConfigTimestamp(userConfigType: UserConfigType, publicKey: String): Long

    fun getGroupAuth(groupId: AccountId): SwarmAuth?
    fun removeGroup(groupId: AccountId)
    fun removeContactOrBlindedContact(address: Address.WithAccountId)

    fun decryptForUser(encoded: ByteArray,
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

    fun deleteGroupConfigs(groupId: AccountId)

}

class ConfigMessage(
    val hash: String,
    val data: ByteArray,
    val timestamp: Long
)

data class ConfigPushResult(
    val hashes: List<String>,
    val timestamp: Long
)

enum class UserConfigType(val namespace: Int) {
    CONTACTS(Namespace.CONTACTS()),
    USER_PROFILE(Namespace.USER_PROFILE()),
    CONVO_INFO_VOLATILE(Namespace.CONVO_INFO_VOLATILE()),
    USER_GROUPS(Namespace.USER_GROUPS()),
}

val ConfigFactoryProtocol.currentUserName: String get() = withUserConfigs { it.userProfile.getName().orEmpty() }
val ConfigFactoryProtocol.currentUserProfile: UserPic? get() = withUserConfigs { configs ->
    configs.userProfile.getPic().takeIf { it.url.isNotBlank() }
}

/**
 * Shortcut to get the group info for a closed group. Equivalent to: `withUserConfigs { it.userGroups.getClosedGroup(groupId) }`
 */
fun ConfigFactoryProtocol.getGroup(groupId: AccountId): GroupInfo.ClosedGroupInfo? {
    return withUserConfigs { it.userGroups.getClosedGroup(groupId.hexString) }
}

/**
 * Flow that emits when the user configs are modified or merged.
 *
 * @param onlyConfigTypes If not null, only emits when the specified config types are updated.
 * @param debounceMills If greater than 0, debounce the emissions by the specified milliseconds
 */
fun ConfigFactoryProtocol.userConfigsChanged(
    onlyConfigTypes: Set<UserConfigType>? = null,
    debounceMills: Long = 0L,
): Flow<ConfigUpdateNotification.UserConfigsUpdated> =
    configUpdateNotifications.filterIsInstance<ConfigUpdateNotification.UserConfigsUpdated>()
        .let { flow ->
            if (debounceMills > 0) {
                flow.debounce(debounceMills)
            } else {
                flow
            }
        }
        .let { flow ->
            if (onlyConfigTypes != null) {
                flow.filter { updated -> updated.updatedTypes.any { it in onlyConfigTypes } }
            } else {
                flow
            }
        }

/** All addresses that exist in config and therefore must be kept. */
fun ConfigFactoryProtocol.allConfigAddresses(): Set<Address> {
    val (contacts, blinded, groups) = withUserConfigs { config ->
        Triple(config.contacts.all(), config.contacts.allBlinded(), config.userGroups.all())
    }

    val contactsAddress : Set<Address> =
        contacts.asSequence().map { Address.fromSerialized(it.id) }.toSet()
    val blindedAddress : Set<Address> =
        blinded.asSequence().map { Address.fromSerialized(it.id) }.toSet()

    val closedIds = mutableListOf<AccountId>()
    val groupAddresses: Set<Address> = buildSet {
        groups.forEach { groupInfo ->
            when (groupInfo) {
                is GroupInfo.LegacyGroupInfo -> {
                    add(Address.LegacyGroup(groupInfo.accountId))
                    groupInfo.members.keys.forEach { add(Address.fromSerialized(it)) }
                }
                is GroupInfo.ClosedGroupInfo -> {
                    val groupId = AccountId(groupInfo.groupAccountId)
                    closedIds += groupId
                    add(Address.Group(groupId))
                }
                is GroupInfo.CommunityGroupInfo -> {
                    add(Address.Community(groupInfo.community.baseUrl, groupInfo.community.room))
                }
            }
        }
    }

    val closedMemberAddresses: Set<Address> = buildSet {
        closedIds.forEach { groupId ->
            withGroupConfigs(groupId) { config ->
                config.groupMembers.all().forEach { add(Address.fromSerialized(it.accountId())) }
            }
        }
    }

    return buildSet {
        addAll(contactsAddress)
        addAll(blindedAddress)
        addAll(groupAddresses)
        addAll(closedMemberAddresses)
    }
}

/**
 * Wait until all configs of given group are pushed to the server.
 *
 * This function is not essential to the pushing of the configs, the config push will schedule
 * itself upon changes, so this function is purely observatory.
 *
 * This function will check the group configs immediately, if nothing needs to be pushed, it will return immediately.
 *
 * @param timeoutMills The maximum time to wait for the group configs to be pushed, in milliseconds. 0 means no timeout.
 * @return True if all group configs are pushed, false if the timeout is reached.
 */
suspend fun ConfigFactoryProtocol.waitUntilGroupConfigsPushed(groupId: AccountId, timeoutMills: Long = 10_000L): Boolean {
    fun needsPush() = withGroupConfigs(groupId) { configs ->
        configs.groupInfo.needsPush() || configs.groupMembers.needsPush()
    }

    val pushed = configUpdateNotifications
        .onStart { emit(ConfigUpdateNotification.GroupConfigsUpdated(groupId, fromMerge = false)) } // Trigger the filtering immediately
        .filter { it is ConfigUpdateNotification.GroupConfigsUpdated && it.groupId == groupId && !needsPush() }

    if (timeoutMills > 0) {
        return withTimeoutOrNull(timeoutMills) { pushed.first() } != null
    } else {
        pushed.first()
        return true
    }
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
    data class UserConfigsUpdated(val updatedTypes: Set<UserConfigType>, val fromMerge: Boolean) : ConfigUpdateNotification

    data class GroupConfigsUpdated(val groupId: AccountId, val fromMerge: Boolean) : ConfigUpdateNotification
}

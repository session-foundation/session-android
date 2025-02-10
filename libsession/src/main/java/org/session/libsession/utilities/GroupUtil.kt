package org.session.libsession.utilities

import network.loki.messenger.libsession_util.util.GroupMember
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import java.io.IOException

object GroupUtil {
    const val LEGACY_CLOSED_GROUP_PREFIX = "__textsecure_group__!"
    const val COMMUNITY_PREFIX = "__loki_public_chat_group__!"
    const val COMMUNITY_INBOX_PREFIX = "__open_group_inbox__!"

    @JvmStatic
    fun getEncodedOpenGroupID(groupID: ByteArray): String {
        return COMMUNITY_PREFIX + Hex.toStringCondensed(groupID)
    }

    @JvmStatic
    fun getEncodedOpenGroupInboxID(openGroup: OpenGroup, accountId: AccountId): Address {
        val openGroupInboxId =
            "${openGroup.server}!${openGroup.publicKey}!${accountId.hexString}".toByteArray()
        return getEncodedOpenGroupInboxID(openGroupInboxId)
    }

    @JvmStatic
    fun getEncodedOpenGroupInboxID(groupInboxID: ByteArray): Address {
        return Address.fromSerialized(COMMUNITY_INBOX_PREFIX + Hex.toStringCondensed(groupInboxID))
    }

    @JvmStatic
    fun getEncodedClosedGroupID(groupID: ByteArray): String {
        val hex = Hex.toStringCondensed(groupID)
        if (hex.startsWith(IdPrefix.GROUP.value)) throw IllegalArgumentException("Trying to encode a new closed group")
        return LEGACY_CLOSED_GROUP_PREFIX + hex
    }

    @JvmStatic
    fun getEncodedId(group: SignalServiceGroup): String {
        val groupId = group.groupId
        if (group.groupType == SignalServiceGroup.GroupType.PUBLIC_CHAT) {
            return getEncodedOpenGroupID(groupId)
        }
        return getEncodedClosedGroupID(groupId)
    }

    private fun splitEncodedGroupID(groupID: String): String {
        if (groupID.split("!").count() > 1) {
            return groupID.split("!", limit = 2)[1]
        }
        return groupID
    }

    @JvmStatic
    fun getDecodedGroupID(groupID: String): String {
        return String(getDecodedGroupIDAsData(groupID))
    }

    @JvmStatic
    fun getDecodedGroupIDAsData(groupID: String): ByteArray {
        return Hex.fromStringCondensed(splitEncodedGroupID(groupID))
    }

    @JvmStatic
    fun getDecodedOpenGroupInboxAccountId(groupID: String): String {
        val decodedGroupId = getDecodedGroupID(groupID)
        if (decodedGroupId.split("!").count() > 2) {
            return decodedGroupId.split("!", limit = 3)[2]
        }
        return decodedGroupId
    }

    @JvmStatic
    fun isCommunity(groupId: String): Boolean {
        return groupId.startsWith(COMMUNITY_PREFIX)
    }

    @JvmStatic
    fun isCommunityInbox(groupId: String): Boolean {
        return groupId.startsWith(COMMUNITY_INBOX_PREFIX)
    }

    @JvmStatic
    fun isLegacyClosedGroup(groupId: String): Boolean {
        return groupId.startsWith(LEGACY_CLOSED_GROUP_PREFIX)
    }

    // NOTE: Signal group ID handling is weird. The ID is double encoded in the database, but not in a `GroupContext`.

    @JvmStatic
    @Throws(IOException::class)
    fun doubleEncodeGroupID(groupPublicKey: String): String {
        if (groupPublicKey.startsWith(IdPrefix.GROUP.value)) throw IllegalArgumentException("Trying to double encode a new closed group")
        return getEncodedClosedGroupID(getEncodedClosedGroupID(Hex.fromStringCondensed(groupPublicKey)).toByteArray())
    }

    @JvmStatic
    fun doubleEncodeGroupID(groupID: ByteArray): String {
        return getEncodedClosedGroupID(getEncodedClosedGroupID(groupID).toByteArray())
    }

    @JvmStatic
    @Throws(IOException::class)
    fun doubleDecodeGroupID(groupID: String): ByteArray {
        return getDecodedGroupIDAsData(getDecodedGroupID(groupID))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun doubleDecodeGroupId(groupID: String): String =
        Hex.toStringCondensed(getDecodedGroupIDAsData(getDecodedGroupID(groupID)))

    @JvmStatic
    fun addressToGroupAccountId(address: Address): String =
        doubleDecodeGroupId(address.toGroupString())

    fun createConfigMemberMap(
        members: Collection<String>,
        admins: Collection<String>
    ): Map<String, Boolean> {
        // Start with admins
        val memberMap = admins.associateWith { true }.toMutableMap()

        // Add the remaining members (there may be duplicates, so only add ones that aren't already in there from admins)
        for (member in members) {
            if (member !in memberMap) {
                memberMap[member] = false
            }
        }
        return memberMap
    }
}

fun GroupMember.getMemberName(config: ConfigFactoryProtocol): String {
    return config.withUserConfigs {
        it.contacts.get(this.accountId.hexString)?.displayName
            ?: this.name.takeIf { it.isNotBlank() }
            ?: truncateIdForDisplay(this.accountId.hexString)
    }
}

//todo GROUPSV2 Currently avatars use Recipients to display avatars, meaning unknown groups member are not currently using this avatar below but instead will try to fetch a recipient from the account ID, which won't be found so the unknown icon will be used - We need to have an avatar system that handles low level daat like a url
fun GroupMember.getMemberAvatar(config: ConfigFactoryProtocol): UserPic {
    return config.withUserConfigs {
        it.contacts.get(this.accountId.hexString)?.profilePicture
            ?: this.profilePic()
            ?: UserPic.DEFAULT
    }
}
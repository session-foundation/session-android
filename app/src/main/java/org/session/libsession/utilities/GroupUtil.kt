package org.session.libsession.utilities

import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import java.io.IOException

private typealias CommunityServerUrl = String
private typealias CommunityPublicKey = String

@Deprecated("This class encodes the group ID unsafely and will soon be removed. All new code should use Address to do address manipulation instead.")
object GroupUtil {
    const val LEGACY_CLOSED_GROUP_PREFIX = "__textsecure_group__!"
    const val COMMUNITY_PREFIX = "__loki_public_chat_group__!"
    const val COMMUNITY_INBOX_PREFIX = "__open_group_inbox__!"

    @JvmStatic
    fun getEncodedOpenGroupID(groupID: ByteArray): String {
        return COMMUNITY_PREFIX + Hex.toStringCondensed(groupID)
    }

    @JvmStatic
    fun getEncodedClosedGroupID(groupID: ByteArray): String {
        val hex = Hex.toStringCondensed(groupID)
        if (hex.startsWith(IdPrefix.GROUP.value)) throw IllegalArgumentException("Trying to encode a new closed group")
        return LEGACY_CLOSED_GROUP_PREFIX + hex
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
    fun getDecodedOpenGroupInboxID(id: String): Triple<CommunityServerUrl, CommunityPublicKey, AccountId>? {
        val decodedGroupId = getDecodedGroupID(id)
        val parts = decodedGroupId.split("!", limit = 3)
        if (parts.size != 3) return null
        return Triple(parts[0], parts[1], AccountId(parts[2]))
    }

    // NOTE: Signal group ID handling is weird. The ID is double encoded in the database, but not in a `GroupContext`.

    @JvmStatic
    @Throws(IOException::class)
    fun doubleEncodeGroupID(groupPublicKey: String): String {
        if (groupPublicKey.startsWith(IdPrefix.GROUP.value)) throw IllegalArgumentException("Trying to double encode a new closed group")
        return getEncodedClosedGroupID(getEncodedClosedGroupID(Hex.fromStringCondensed(groupPublicKey)).toByteArray())
    }

    @JvmStatic
    @Throws(IOException::class)
    fun doubleDecodeGroupId(groupID: String): String =
        Hex.toStringCondensed(getDecodedGroupIDAsData(getDecodedGroupID(groupID)))

}
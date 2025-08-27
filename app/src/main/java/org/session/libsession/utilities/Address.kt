package org.session.libsession.utilities

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Util
import java.util.LinkedList
import org.session.libsignal.utilities.Log
import kotlin.text.startsWith

@Serializable(with = AddressSerializer::class)
sealed interface Address : Parcelable, Comparable<Address> {
    /**
     * The serialized form of the address.
     */
    val address: String

    /**
     * A debug string that is safe to log.
     */
    val debugString: String

    override fun compareTo(other: Address) = address.compareTo(other.address)

    @Serializable(with = GroupAddressSerializer::class)
    data class Group(override val accountId: AccountId) : Conversable, GroupLike, WithAccountId {
        override val address: String
            get() = accountId.hexString

        override val debugString: String
            get() = accountId.toString()

        init {
            check(accountId.prefix == IdPrefix.GROUP) {
                "AccountId must have a GROUP prefix, but was: ${accountId.prefix}"
            }
        }

        override fun toString(): String = address
    }

    @Serializable(with = StandardAddressSerializer::class)
    data class Standard(override val accountId: AccountId) : Conversable, WithAccountId {
        override val address: String
            get() = accountId.hexString

        override val debugString: String
            get() = accountId.toString()

        init {
            check(accountId.prefix == IdPrefix.STANDARD) {
                "AccountId must have a STANDARD prefix, but was: ${accountId.prefix}"
            }
        }

        override fun toString(): String = address
    }

    data class Blinded(val blindedId: AccountId) : Address, WithAccountId {
        override val accountId: AccountId
            get() = blindedId

        override val address: String
            get() = blindedId.hexString

        override val debugString: String
            get() = blindedId.toString()

        init {
            check(blindedId.prefix?.isBlinded() == true) {
                "AccountId must have a BLINDED prefix, but was: ${blindedId.prefix}"
            }
        }

        override fun toString(): String = address
    }

    data class LegacyGroup(val groupPublicKeyHex: String) : Conversable, GroupLike {
        override val address: String by lazy(LazyThreadSafetyMode.NONE) {
            GroupUtil.doubleEncodeGroupID(groupPublicKeyHex)
        }

        override val debugString: String
            get() = "LegacyGroup(groupPublicKeyHex=${groupPublicKeyHex.substring(0, 8)}...)"

        override fun toString(): String = address
    }

    data class CommunityBlindedId(val serverUrl: String, val blindedId: Blinded) : Conversable, WithAccountId {
        init {
            check(serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                "Server URL must start with http:// or https://, but was: $serverUrl"
            }
        }

        override val accountId: AccountId
            get() = blindedId.blindedId

        override val address: String by lazy(LazyThreadSafetyMode.NONE) {
            "${URI_PREFIX}${blindedId.blindedId.hexString}"
                .toUri()
                .buildUpon()
                .appendQueryParameter(URL_QUERY_SERVER, serverUrl)
                .build()
                .toString()
        }

        override val debugString: String
            get() = "CommunityBlindedId(" +
                    "serverUrl=${serverUrl.substring(0, 10)}, " +
                    "blindedId=${blindedId.debugString})"

        override fun toString(): String = address

        companion object {
            const val URI_PREFIX = "community-blinded://"
            const val URL_QUERY_SERVER = "server"
        }
    }

    data class Community(val serverUrl: String, val room: String) : Conversable, GroupLike {
        constructor(openGroup: OpenGroup): this(
            serverUrl = openGroup.server,
            room = openGroup.room
        )

        init {
            check(serverUrl.startsWith("http://") ||
                    serverUrl.startsWith("https://")) {
                "Server URL must start with http:// or https://, but was: $serverUrl"
            }
        }

        override val address: String by lazy(LazyThreadSafetyMode.NONE) {
            "${URI_PREFIX}${Uri.encode(serverUrl)}"
                .toUri()
                .buildUpon()
                .appendQueryParameter(URL_QUERY_ROOM, room)
                .build()
                .toString()
        }

        override val debugString: String
            get() = "Community(serverUrl=${serverUrl.substring(0, 10)}, room=xxxx)"

        override fun toString(): String = address

        companion object {
            const val URI_PREFIX = "community://"
            const val URL_QUERY_ROOM = "room"
        }
    }

    data class Unknown(val serialized: String) : Address {
        override val address: String
            get() = serialized

        override val debugString: String
            get() = "Unknown(serialized=$serialized)"

        override fun toString(): String = address
    }

    /**
     * A marker interface for addresses that can be used to start a conversation
     */
    @Serializable(with = ConversableAddressSerializer::class)
    sealed interface Conversable : Address

    /**
     * A marker interface for addresses that represent a group-like entity.
     */
    sealed interface GroupLike : Address

    sealed interface WithAccountId {
        val accountId: AccountId
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(address)
    }

    companion object {
        @JvmStatic
        fun fromSerialized(serialized: String): Address {
            try {
                if (serialized.startsWith(Community.URI_PREFIX)) {
                    val uri = serialized.toUri()
                    val serverUrl = requireNotNull(uri.host?.takeIf { it.isNotBlank() }) {
                        "Invalid Community address: missing server URL"
                    }

                    val room = requireNotNull(uri.getQueryParameter(Community.URL_QUERY_ROOM)) {
                        "Invalid Community address: missing room query parameter"
                    }

                    // If we have a room, we can create a Community address
                    return Community(
                        serverUrl = serverUrl,
                        room = room
                    )
                }

                if (serialized.startsWith(CommunityBlindedId.URI_PREFIX)) {
                    val uri = serialized.toUri()
                    val blinded = Blinded(AccountId(uri.host.orEmpty()))
                    val server = checkNotNull(uri.getQueryParameter(CommunityBlindedId.URL_QUERY_SERVER)) {
                        "Invalid CommunityBlindedId: missing server URL query parameter"
                    }

                    return CommunityBlindedId(
                        serverUrl = server,
                        blindedId = blinded
                    )
                }

                if (serialized.startsWith(GroupUtil.LEGACY_CLOSED_GROUP_PREFIX)) {
                    val groupId = GroupUtil.doubleDecodeGroupId(serialized)
                    return LegacyGroup(groupId)
                }

                return AccountId(serialized).toAddress()
            } catch (e: Exception) {
                Log.w("Address", "Failed to parse address: $serialized", e)
                return Unknown(serialized)
            }
        }

        @JvmStatic
        fun fromSerializedList(serialized: String, delimiter: Char): List<Address> {
            val escapedAddresses = DelimiterUtil.split(serialized, delimiter)
            val set = escapedAddresses.toSet().sorted()
            val addresses: MutableList<Address> = LinkedList()
            for (escapedAddress in set) {
                addresses.add(fromSerialized(DelimiterUtil.unescape(escapedAddress, delimiter)))
            }
            return addresses
        }

        @JvmStatic
        fun toSerializedList(addresses: List<Address>, delimiter: Char): String {
            val set = addresses.toSet().sorted()
            val escapedAddresses: MutableList<String> = LinkedList()
            for (address in set) {
                escapedAddresses.add(DelimiterUtil.escape(address.toString(), delimiter))
            }
            return Util.join(escapedAddresses, delimiter.toString() + "")
        }

        fun String.toAddress(): Address {
            return fromSerialized(this)
        }

        fun AccountId.toAddress(): Address {
            return when (prefix) {
                IdPrefix.GROUP -> Group(this)
                IdPrefix.STANDARD -> Standard(this)
                IdPrefix.BLINDED, IdPrefix.BLINDEDV2 -> Blinded(this)
                else -> throw IllegalArgumentException("Unknown address prefix: $prefix")
            }
        }
        @JvmField
        val CREATOR: Parcelable.Creator<Address> = object : Parcelable.Creator<Address> {
            override fun createFromParcel(parcel: Parcel): Address {
                val address = requireNotNull(parcel.readString()) {
                    "Invalid address from parcel. Must not be null."
                }

                return fromSerialized(address)
            }

            override fun newArray(size: Int): Array<Address?> = arrayOfNulls(size)
        }
    }
}

val Address.isStandard: Boolean
    get() = this is Address.Standard

val Address.isLegacyGroup: Boolean
    get() = this is Address.LegacyGroup

val Address.isGroupV2: Boolean
    get() = this is Address.Group

val Address.isCommunity: Boolean
    get() = this is Address.Community

val Address.isCommunityInbox: Boolean
    get() = this is Address.CommunityBlindedId

val Address.isGroup: Boolean
    get() = this is Address.Group || this is Address.LegacyGroup

val Address.isGroupOrCommunity: Boolean
    get() = isGroup || isCommunity

val Address.isBlinded: Boolean
    get() = this is Address.Blinded

/**
 * Converts this address to a blind [AccountId] if this address contains a blinded ID.
 */
fun Address.toBlinded(): Address.Blinded? {
    return (this as? Address.Blinded)
        ?: (this as? Address.CommunityBlindedId)?.blindedId
}

@Deprecated("This is a legacy way of getting a confusing term: groupString. Use the explicit address subclasses to state your intent.")
fun Address.toGroupString(): String {
    return when (this) {
        is Address.LegacyGroup, is Address.Community, is Address.Group -> address
        else -> throw IllegalArgumentException("Address is not a group: $this")
    }
}
package org.session.libsession.utilities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Util
import java.util.LinkedList

@Parcelize
data class Address private constructor(val address: String) : Parcelable, Comparable<Address> {
    val isLegacyGroup: Boolean
        get() = GroupUtil.isLegacyClosedGroup(address)
    val isGroupV2: Boolean
        get() = address.startsWith(IdPrefix.GROUP.value)
    val isCommunity: Boolean
        get() = GroupUtil.isCommunity(address)
    val isCommunityInbox: Boolean
        get() = GroupUtil.isCommunityInbox(address)
    val isCommunityOutbox: Boolean
        get() = address.startsWith(IdPrefix.BLINDED.value) || address.startsWith(IdPrefix.BLINDEDV2.value)
    val isGroupOrCommunity: Boolean
        get() = isGroup || isCommunity
    val isGroup: Boolean
        get() = isLegacyGroup || isGroupV2
    val isContact: Boolean
        get() = !(isGroupOrCommunity || isCommunityInbox)

    fun contactIdentifier(): String {
        if (!isContact && !isCommunity) {
            if (isGroupOrCommunity) throw AssertionError("Not e164, is group")
            throw AssertionError("Not e164, unknown")
        }
        return address
    }

    fun toGroupString(): String {
        if (!isGroupOrCommunity) throw AssertionError("Not group")
        return address
    }

    override fun toString(): String = address

    override fun compareTo(other: Address): Int = address.compareTo(other.address)

    val debugString: String
        get() = "Address(address=${address.substring(0, address.length.coerceAtMost(5))}...)"

    companion object {
        val UNKNOWN = Address("Unknown")

        @JvmStatic
        fun fromSerialized(serialized: String): Address = Address(serialized.lowercase())

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

        fun AccountId.toAddress(): Address {
            return fromSerialized(hexString)
        }
    }

}
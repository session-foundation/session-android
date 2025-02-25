package org.session.libsession.utilities

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.Pair
import androidx.annotation.VisibleForTesting
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Util
import org.session.libsignal.utilities.guava.Optional
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Matcher
import java.util.regex.Pattern

class Address private constructor(address: String) : Parcelable, Comparable<Address?> {
    private val address: String = address.toLowerCase()

    constructor(`in`: Parcel) : this(`in`.readString()!!) {}

    val isGroup: Boolean
        get() = GroupUtil.isEncodedGroup(address)
    val isClosedGroup: Boolean
        get() = GroupUtil.isClosedGroup(address)
    val isCommunity: Boolean
        get() = GroupUtil.isCommunity(address)
    val isCommunityInbox: Boolean
        get() = GroupUtil.isCommunityInbox(address)
    val isCommunityOutbox: Boolean
        get() = address.startsWith(IdPrefix.BLINDED.value) || address.startsWith(IdPrefix.BLINDEDV2.value)
    val isContact: Boolean
        get() = !(isGroup || isCommunityInbox)

    fun contactIdentifier(): String {
        if (!isContact && !isCommunity) {
            if (isGroup) throw AssertionError("Not e164, is group")
            throw AssertionError("Not e164, unknown")
        }
        return address
    }

    fun toGroupString(): String {
        if (!isGroup) throw AssertionError("Not group")
        return address
    }

    override fun toString(): String {
        return address
    }

    fun serialize(): String {
        return address
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return if (other == null || other !is Address) false else address == other.address
    }

    override fun hashCode(): Int {
        return address.hashCode()
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(address)
    }

    override fun compareTo(other: Address?): Int {
        return address.compareTo(other?.address!!)
    }

    @VisibleForTesting
    class ExternalAddressFormatter internal constructor(localCountryCode: String, countryCode: Boolean) {
        private val localNumber: Optional<PhoneNumber>
        private val localCountryCode: String
        private val ALPHA_PATTERN = Pattern.compile("[a-zA-Z]")
        fun format(number: String?): String {
            return number ?: "Unknown"
        }

        private fun parseAreaCode(e164Number: String, countryCode: Int): String? {
            when (countryCode) {
                1 -> return e164Number.substring(2, 5)
                55 -> return e164Number.substring(3, 5)
            }
            return null
        }

        private fun applyAreaCodeRules(localNumber: Optional<PhoneNumber>, testNumber: String): String {
            if (!localNumber.isPresent || !localNumber.get().areaCode.isPresent) {
                return testNumber
            }
            val matcher: Matcher
            when (localNumber.get().countryCode) {
                1 -> {
                    matcher = US_NO_AREACODE.matcher(testNumber)
                    if (matcher.matches()) {
                        return localNumber.get().areaCode.toString() + matcher.group()
                    }
                }
                55 -> {
                    matcher = BR_NO_AREACODE.matcher(testNumber)
                    if (matcher.matches()) {
                        return localNumber.get().areaCode.toString() + matcher.group()
                    }
                }
            }
            return testNumber
        }

        private class PhoneNumber internal constructor(val e164Number: String, val countryCode: Int, areaCode: String?) {
            val areaCode: Optional<String?>

            init {
                this.areaCode = Optional.fromNullable(areaCode)
            }
        }

        companion object {
            private val TAG = ExternalAddressFormatter::class.java.simpleName
            private val SHORT_COUNTRIES: HashSet<String?> = object : HashSet<String?>() {
                init {
                    add("NU")
                    add("TK")
                    add("NC")
                    add("AC")
                }
            }
            private val US_NO_AREACODE = Pattern.compile("^(\\d{7})$")
            private val BR_NO_AREACODE = Pattern.compile("^(9?\\d{8})$")
        }

        init {
            localNumber = Optional.absent()
            this.localCountryCode = localCountryCode
        }
    }

    companion object {
        @JvmField val CREATOR: Parcelable.Creator<Address?> = object : Parcelable.Creator<Address?> {
            override fun createFromParcel(`in`: Parcel): Address {
                return Address(`in`)
            }

            override fun newArray(size: Int): Array<Address?> {
                return arrayOfNulls(size)
            }
        }
        val UNKNOWN = Address("Unknown")
        private val TAG = Address::class.java.simpleName
        private val cachedFormatter = AtomicReference<Pair<String, ExternalAddressFormatter>>()

        @JvmStatic
        fun fromSerialized(serialized: String): Address {
            return Address(serialized)
        }

        @JvmStatic
        fun fromExternal(context: Context, external: String?): Address {
            return fromSerialized(external!!)
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
                escapedAddresses.add(DelimiterUtil.escape(address.serialize(), delimiter))
            }
            return Util.join(escapedAddresses, delimiter.toString() + "")
        }
    }

}
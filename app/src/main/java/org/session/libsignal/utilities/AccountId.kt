package org.session.libsignal.utilities

import network.loki.messenger.BuildConfig
import org.session.libsession.utilities.truncatedForDisplay


private val VALID_ACCOUNT_ID_PATTERN = Regex("[0-9]{2}[0-9a-fA-F]{64}")

data class AccountId(
    val hexString: String,
): Comparable<AccountId> {
    constructor(prefix: IdPrefix, publicKey: ByteArray):
        this(
            hexString = prefix.value + publicKey.toHexString()
        )

    init {
        if (BuildConfig.DEBUG) {
            check(VALID_ACCOUNT_ID_PATTERN.matches(hexString)) {
                "Invalid account ID: $hexString"
            }
        }
    }

    val prefix: IdPrefix? get() = IdPrefix.fromValue(hexString)

    /**
     * The public key bytes with the prefix removed.
     *
     * Note: the type of public key varies depending on the prefix. For STANDARD,
     * it's an Curve25519 public key, otherwise, it should be an Ed25519 public key.
     */
    val pubKeyBytes: ByteArray by lazy {
        Hex.fromStringCondensed(hexString.drop(2))
    }

    override fun toString(): String {
        return truncatedForDisplay()
    }

    override fun compareTo(other: AccountId): Int {
        return hexString.compareTo(other.hexString)
    }

    companion object {
        fun fromStringOrNull(accountId: String): AccountId? {
            return if (VALID_ACCOUNT_ID_PATTERN.matches(accountId)) {
                AccountId(accountId)
            } else {
                null
            }
        }
    }
}

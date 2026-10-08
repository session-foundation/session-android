package org.thoughtcrime.securesms.pro

import org.session.libsignal.utilities.toHexString

/**
 * Derives the `obfuscatedAccountId` we hand to Play Billing from the Pro master key.
 *
 * The backend binds a redeem by **byte equality against the request-signed master pubkey**
 * (`obfuscated_account_id == bytes(master_pkey)`), so the tag is the raw 32-byte Ed25519 public
 * key, hex-encoded — *not* a hash of it. The backend accepts 64 hex chars with an optional `0x`
 * prefix; we send the bare lowercase form.
 *
 * Do not reintroduce a digest here: both a hash and the raw key are 64 hex chars, so a mismatch
 * decodes cleanly and fails silently on *value* — every purchase would fail to redeem with
 * `unknown_payment`.
 */
object PlayStoreAccountId {
    private const val ED25519_SECRET_KEY_LENGTH = 64
    private const val ED25519_PUBLIC_KEY_LENGTH = 32
    private const val ED25519_PUBLIC_KEY_OFFSET = 32

    fun fromProMasterPrivateKey(proMasterPrivateKey: ByteArray): String {
        require(proMasterPrivateKey.size == ED25519_SECRET_KEY_LENGTH) {
            "Expected a $ED25519_SECRET_KEY_LENGTH-byte Ed25519 key, got ${proMasterPrivateKey.size} bytes"
        }

        return fromEd25519PublicKey(
            proMasterPrivateKey.copyOfRange(
                ED25519_PUBLIC_KEY_OFFSET,
                ED25519_SECRET_KEY_LENGTH
            )
        )
    }

    fun fromEd25519PublicKey(ed25519PublicKey: ByteArray): String {
        require(ed25519PublicKey.size == ED25519_PUBLIC_KEY_LENGTH) {
            "Expected a $ED25519_PUBLIC_KEY_LENGTH-byte Ed25519 public key, got ${ed25519PublicKey.size} bytes"
        }

        return ed25519PublicKey.toHexString()
    }
}

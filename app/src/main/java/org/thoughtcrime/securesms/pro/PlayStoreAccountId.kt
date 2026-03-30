package org.thoughtcrime.securesms.pro

import org.session.libsignal.utilities.toHexString
import java.security.MessageDigest

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

        return MessageDigest
            .getInstance("SHA-256")
            .digest(ed25519PublicKey)
            .toHexString()
    }
}

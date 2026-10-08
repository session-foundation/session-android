package org.thoughtcrime.securesms.pro

import junit.framework.TestCase.assertEquals
import org.junit.Assert.assertNotEquals
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.toHexString
import org.junit.Test
import java.security.MessageDigest

/**
 * The backend binds a Google Play redeem with `obfuscated_account_id == bytes(master_pkey)`
 * (`backend.py`, `redeem_payment`), decoding Play's `obfuscatedExternalAccountId` as 64 hex chars
 * → 32 bytes. So the account id must be the **raw** Pro master Ed25519 public key in hex.
 *
 * Two earlier schemes both produced 64 hex chars and so decoded cleanly while failing on value
 * (every purchase → `unknown_payment`). Both are pinned as negative cases below.
 */
class PlayStoreAccountIdTest {
    private val proMasterPrivateKey = Hex.fromStringCondensed(
        "728db9098cc9095de5a4838c884a2920e04ec46a568693047d54f6a0fe3d5718" +
            "83d36e9ae51851014f686bd6089dc93061292d1f516e65b44f1d3f6bb7504a81"
    )
    private val ed25519PublicKeyHex =
        "83d36e9ae51851014f686bd6089dc93061292d1f516e65b44f1d3f6bb7504a81"
    private val ed25519PublicKey = Hex.fromStringCondensed(ed25519PublicKeyHex)

    @Test
    fun uses_raw_public_component_of_pro_master_key() {
        assertEquals(
            ed25519PublicKeyHex,
            PlayStoreAccountId.fromProMasterPrivateKey(proMasterPrivateKey)
        )
        assertEquals(
            ed25519PublicKeyHex,
            PlayStoreAccountId.fromEd25519PublicKey(ed25519PublicKey)
        )
    }

    @Test
    fun is_the_64_char_bare_lowercase_hex_the_backend_decodes() {
        val accountId = PlayStoreAccountId.fromProMasterPrivateKey(proMasterPrivateKey)

        // Google caps obfuscatedAccountId at 64 chars, and the backend requires exactly 64
        // (after stripping an optional "0x") before `bytes.fromhex`.
        assertEquals(64, accountId.length)
        assertEquals(accountId.lowercase(), accountId)
        assertEquals(false, accountId.startsWith("0x"))
        assertEquals(
            ed25519PublicKey.toList(),
            Hex.fromStringCondensed(accountId).toList()
        )
    }

    @Test
    fun does_not_hash_the_public_key() {
        // Superseded scheme (backend commit 60ea9f6 dropped the sha256): also 64 hex chars, so a
        // regression here would decode fine and silently fail to redeem.
        val supersededPubKeyHash = MessageDigest
            .getInstance("SHA-256")
            .digest(ed25519PublicKey)
            .toHexString()

        assertEquals(
            "d62b1dd03833e4fee9cd2cee95014520da37fd15c86bb422d221a324193a326b",
            supersededPubKeyHash
        )
        assertNotEquals(
            supersededPubKeyHash,
            PlayStoreAccountId.fromProMasterPrivateKey(proMasterPrivateKey)
        )
    }

    @Test
    fun does_not_hash_the_private_key_hex() {
        // The original (pre-3c1cb22) scheme: sha256 of the hex *string* of the 64-byte secret key.
        val legacyAndroidAccountId = MessageDigest
            .getInstance("SHA-256")
            .digest(proMasterPrivateKey.toHexString().toByteArray(Charsets.UTF_8))
            .toHexString()

        assertEquals(
            "e062990b72bd6870ab27c0d3d6f4db7f729b769a76c7fff89e39ba39f8d5b957",
            legacyAndroidAccountId
        )
        assertNotEquals(
            legacyAndroidAccountId,
            PlayStoreAccountId.fromProMasterPrivateKey(proMasterPrivateKey)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_a_secret_key_of_the_wrong_length() {
        PlayStoreAccountId.fromProMasterPrivateKey(ed25519PublicKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_a_public_key_of_the_wrong_length() {
        PlayStoreAccountId.fromEd25519PublicKey(proMasterPrivateKey)
    }
}

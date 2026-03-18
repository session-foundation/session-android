package org.thoughtcrime.securesms.pro

import junit.framework.TestCase.assertEquals
import org.junit.Assert.assertNotEquals
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.toHexString
import org.junit.Test
import java.security.MessageDigest

class PlayStoreAccountIdTest {
    @Test
    fun hashes_public_component_of_pro_master_key() {
        val proMasterPrivateKey = Hex.fromStringCondensed(
            "728db9098cc9095de5a4838c884a2920e04ec46a568693047d54f6a0fe3d5718" +
                "83d36e9ae51851014f686bd6089dc93061292d1f516e65b44f1d3f6bb7504a81"
        )
        val ed25519PublicKey = Hex.fromStringCondensed(
            "83d36e9ae51851014f686bd6089dc93061292d1f516e65b44f1d3f6bb7504a81"
        )

        val legacyAndroidAccountId = MessageDigest
            .getInstance("SHA-256")
            .digest(proMasterPrivateKey.toHexString().toByteArray(Charsets.UTF_8))
            .toHexString()

        assertEquals(
            "d62b1dd03833e4fee9cd2cee95014520da37fd15c86bb422d221a324193a326b",
            PlayStoreAccountId.fromProMasterPrivateKey(proMasterPrivateKey)
        )
        assertEquals(
            "d62b1dd03833e4fee9cd2cee95014520da37fd15c86bb422d221a324193a326b",
            PlayStoreAccountId.fromEd25519PublicKey(ed25519PublicKey)
        )
        assertEquals(
            "e062990b72bd6870ab27c0d3d6f4db7f729b769a76c7fff89e39ba39f8d5b957",
            legacyAndroidAccountId
        )
        assertNotEquals(
            legacyAndroidAccountId,
            PlayStoreAccountId.fromProMasterPrivateKey(proMasterPrivateKey)
        )
    }
}

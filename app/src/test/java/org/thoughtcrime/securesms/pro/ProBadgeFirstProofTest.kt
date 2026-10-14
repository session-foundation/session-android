package org.thoughtcrime.securesms.pro

import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import network.loki.messenger.libsession_util.MutableUserProfile
import network.loki.messenger.libsession_util.pro.ProConfig
import network.loki.messenger.libsession_util.pro.ProProof
import network.loki.messenger.libsession_util.protocol.ProProfileFeature
import network.loki.messenger.libsession_util.protocol.ProProfileFeatures
import network.loki.messenger.libsession_util.util.toBitSet
import org.junit.Test
import org.thoughtcrime.securesms.pro.ProProofGenerationWorker.Companion.storeProof

/**
 * Covers the pro badge being enabled on an account's first-ever proof.
 *
 * The badge is off by default and the settings toggle is the user's own choice, so the guard asks
 * whether this account has EVER held Pro rather than whether it holds Pro now. The second question
 * is the one that ships easily and is annoying to diagnose: a subscriber who turns the badge off has
 * it turned back on at the next renewal, forever, with no way to make it stick. The cases below that
 * assert the badge is NOT touched are the ones carrying that regression.
 */
class ProBadgeFirstProofTest {

    private val rotatingPrivateKey = ByteArray(64) { 1 }

    @Test
    fun `a first-ever proof turns the pro badge on`() {
        val userProfile = neverHadPro()

        storeProof(userProfile, proof(expirySeconds = 2_000), rotatingPrivateKey)

        verify(exactly = 1) { userProfile.setProBadge(true) }
        verify(exactly = 1) { userProfile.setProConfig(any()) }
    }

    @Test
    fun `a subscriber who turned the badge off keeps it off when the proof renews`() {
        val userProfile = userProfile(
            proof = proof(expirySeconds = 1_000),
            accessExpirySeconds = 1_500L
        )

        storeProof(userProfile, proof(expirySeconds = 2_000), rotatingPrivateKey)

        verify(exactly = 0) { userProfile.setProBadge(any()) }
        verify(exactly = 1) { userProfile.setProConfig(any()) }
    }

    @Test
    fun `a lapsed subscriber who turned the badge off keeps it off when the plan is renewed`() {
        // The proof is cleared when a plan lapses, so the access expiry is the only evidence left that
        // this account has been Pro before — and the only thing standing between the user's choice and
        // a fresh proof reinstating the badge.
        val userProfile = userProfile(proof = null, accessExpirySeconds = 1_500L)

        storeProof(userProfile, proof(expirySeconds = 2_000), rotatingPrivateKey)

        verify(exactly = 0) { userProfile.setProBadge(any()) }
        verify(exactly = 1) { userProfile.setProConfig(any()) }
    }

    @Test
    fun `a profile feature already in config means the account has been Pro before`() {
        val userProfile = userProfile(
            features = listOf(ProProfileFeature.ANIMATED_AVATAR).toBitSet()
        )

        storeProof(userProfile, proof(expirySeconds = 2_000), rotatingPrivateKey)

        verify(exactly = 0) { userProfile.setProBadge(any()) }
    }

    @Test
    fun `a proof that does not extend coverage is not stored, and enables nothing`() {
        val userProfile = userProfile(
            proof = proof(expirySeconds = 3_000),
            accessExpirySeconds = 3_500L
        )

        storeProof(userProfile, proof(expirySeconds = 2_000), rotatingPrivateKey)

        verify(exactly = 0) { userProfile.setProConfig(any()) }
        verify(exactly = 0) { userProfile.setProBadge(any()) }
    }

    private fun neverHadPro(): MutableUserProfile = userProfile()

    private fun userProfile(
        proof: ProProof? = null,
        accessExpirySeconds: Long? = null,
        features: ProProfileFeatures = emptyList<ProProfileFeature>().toBitSet(),
    ): MutableUserProfile = mockk(relaxed = true) {
        every { getProConfig() } returns proof?.let { ProConfig(it, rotatingPrivateKey) }
        every { getProAccessExpiry() } returns accessExpirySeconds
        every { getProFeatures() } returns features
    }

    private fun proof(expirySeconds: Long): ProProof = ProProof(
        revocationTagHex = "aa".repeat(32),
        rotatingPubKeyHex = "bb".repeat(32),
        expirySeconds = expirySeconds,
        signatureHex = "cc".repeat(64),
    )
}

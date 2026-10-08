package org.thoughtcrime.securesms.pro

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.pro.ProStatusManager.Companion.seededDisplayStatus
import java.time.Duration
import java.time.Instant

/**
 * The plan state implied by synced config alone, as a pure function of (access expiry, proof expiry, now).
 *
 * Scope: the ordering and the four outcomes. Reading config and choosing to seed at all are the caller's.
 *
 * The ordering is the point of these tests. It is the same on iOS and Desktop, so a change here is a
 * cross-client divergence rather than an Android preference, and the case that pins it is a valid proof
 * under a past access expiry: proof-first collapses that into active and the state stops being
 * expressible.
 */
class ProSeededDisplayStatusTest {

    private val now: Instant = Instant.parse("2026-08-17T00:00:00Z")

    private fun inDays(days: Long): Instant = now.plus(Duration.ofDays(days))
    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    // --- the access expiry decides whenever it is present ---------------------------------------

    @Test
    fun `a future access expiry is active`() {
        assertEquals(
            ProStatus.Active.FromLocalState,
            seededDisplayStatus(accessExpiry = inDays(30), proofExpiry = null, now = now)
        )
    }

    @Test
    fun `a past access expiry is expired`() {
        assertEquals(
            ProStatus.Expired.FromLocalState,
            seededDisplayStatus(accessExpiry = daysAgo(1), proofExpiry = null, now = now)
        )
    }

    @Test
    fun `an access expiry with no proof is not never-subscribed`() {
        // The restored-device case: config carried the plan before the credential. Answering
        // NeverSubscribed here is what put "Upgrade Session" in front of a paying subscriber.
        val seeded = seededDisplayStatus(accessExpiry = inDays(30), proofExpiry = null, now = now)
        assertEquals(ProStatus.Active.FromLocalState, seeded)
    }

    // --- the overhang, which only this ordering can express -------------------------------------

    @Test
    fun `a valid proof under a past access expiry displays as expired`() {
        // Display expired while access continues until the proof lapses. Consulting the proof first
        // would answer active and the state would be unrepresentable.
        assertEquals(
            ProStatus.Expired.FromLocalState,
            seededDisplayStatus(accessExpiry = daysAgo(1), proofExpiry = inDays(7), now = now)
        )
    }

    @Test
    fun `a future access expiry wins over an expired proof`() {
        // The mirror: the plan is paid up while this device's credential has lapsed between renewals.
        assertEquals(
            ProStatus.Active.FromLocalState,
            seededDisplayStatus(accessExpiry = inDays(30), proofExpiry = daysAgo(1), now = now)
        )
    }

    // --- the proof is the fallback --------------------------------------------------------------

    @Test
    fun `a valid proof with no access expiry is active`() {
        assertEquals(
            ProStatus.Active.FromLocalState,
            seededDisplayStatus(accessExpiry = null, proofExpiry = inDays(7), now = now)
        )
    }

    @Test
    fun `an expired proof with no access expiry is expired, not never-subscribed`() {
        // A lapsed credential still evidences a subscription that existed. NeverSubscribed asserts the
        // account never had one, which is a different and stronger claim than the local state supports.
        assertEquals(
            ProStatus.Expired.FromLocalState,
            seededDisplayStatus(accessExpiry = null, proofExpiry = daysAgo(1), now = now)
        )
    }

    // --- neither -------------------------------------------------------------------------------

    @Test
    fun `no access expiry and no proof is never subscribed`() {
        assertEquals(
            ProStatus.NeverSubscribed,
            seededDisplayStatus(accessExpiry = null, proofExpiry = null, now = now)
        )
    }

    @Test
    fun `the boundary is exclusive on both branches`() {
        // An expiry exactly at now is past, not future — the same comparison on both branches so they
        // cannot disagree about the instant they share.
        assertEquals(
            ProStatus.Expired.FromLocalState,
            seededDisplayStatus(accessExpiry = now, proofExpiry = null, now = now)
        )
        assertEquals(
            ProStatus.Expired.FromLocalState,
            seededDisplayStatus(accessExpiry = null, proofExpiry = now, now = now)
        )
    }
}

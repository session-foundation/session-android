package org.thoughtcrime.securesms.pro

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.thoughtcrime.securesms.pro.ProStatusManager.Companion.startupFetchReason
import java.time.Duration
import java.time.Instant

/**
 * The startup gate's decision (spec §2 trigger #1 / §4), as a pure function of
 * (access expiry, auto-renewing, now).
 *
 * Scope: these cover the decision only. They do NOT cover the 24h persisted interval or the config
 * read — both need a database, and the interval is checked before this function is reached.
 *
 * **Two of these tests pin behaviour we believe is WRONG**, deliberately: the F8 row-1 blind spot and
 * the F2 held row. They are written so that the day either ruling lands, the test fails and points at
 * the decision rather than silently accepting a change. Read their comments before "fixing" them.
 */
class ProStartupGateTest {

    private val now: Instant = Instant.parse("2026-08-07T00:00:00Z")

    private fun inDays(days: Long): Instant = now.plus(Duration.ofDays(days))
    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    // --- never subscribed -----------------------------------------------------------------------

    @Test
    fun `no access expiry means no fetch`() {
        // The population the gate exists for: users who have never subscribed were fetching on every
        // cold start and could never see a CTA.
        assertNull(startupFetchReason(accessExpiry = null, autoRenewing = false, now = now))
        assertNull(startupFetchReason(accessExpiry = null, autoRenewing = true, now = now))
    }

    // --- rows 1 and 2: auto-renewing ------------------------------------------------------------

    @Test
    fun `row 1 - auto-renewing and comfortably active does not fetch`() {
        assertNull(startupFetchReason(inDays(20), autoRenewing = true, now = now))
    }

    @Test
    fun `row 2 - auto-renewing and past the access expiry fetches`() {
        // Grace is not derivable from config, so the only way to learn where we stand is to ask.
        assertNotNull(startupFetchReason(daysAgo(1), autoRenewing = true, now = now))
    }

    @Test
    fun `row 2 boundary - exactly at the access expiry fetches`() {
        assertNotNull(startupFetchReason(now, autoRenewing = true, now = now))
    }

    // --- rows 3 and 4: not auto-renewing --------------------------------------------------------

    @Test
    fun `row 3 - expiring inside the CTA window fetches`() {
        assertNotNull(startupFetchReason(inDays(3), autoRenewing = false, now = now))
    }

    @Test
    fun `row 3 boundary - just outside the 7 day window does not fetch`() {
        assertNull(startupFetchReason(inDays(8), autoRenewing = false, now = now))
    }

    @Test
    fun `row 4 - recently expired fetches to confirm before the Expired CTA`() {
        // Config can read expired while a renewal landed on another device and hasn't synced, so the
        // Expired CTA must never fire off config alone.
        assertNotNull(startupFetchReason(daysAgo(5), autoRenewing = false, now = now))
    }

    @Test
    fun `row 4 boundary - expired longer ago than the CTA window does not fetch`() {
        // Past 30 days the Expired CTA can no longer fire, so a confirming fetch has no consumer.
        assertNull(startupFetchReason(daysAgo(31), autoRenewing = false, now = now))
    }

    // --- the two seams: pinned as-built, both believed wrong -------------------------------------

    @Test
    fun `F8 SEAM - row 1 does not fetch during the real grace window, and that is the known flaw`() {
        // `E` is grace-INCLUSIVE, so the real grace window (E - grace <= now < E) lies entirely
        // inside row 1's `now < E`. An auto-renewing user whose renewal is overdue but who is still
        // covered is exactly the state this redesign exists to surface — and the gate declines to
        // fetch for them.
        //
        // Pinned so the blind spot is visible rather than incidental. When F8 is ruled on and the
        // boundary moves to `E - grace`, this assertion SHOULD fail — at which point the fix is to
        // change the gate and invert this test, not to delete it.
        val graceWindow = startupFetchReason(inDays(1), autoRenewing = true, now = now)
        assertNull(graceWindow)
    }

    @Test
    fun `F2 SEAM - not auto-renewing, active, outside the CTA window does not fetch`() {
        // The held row. Returns null per the spec's letter ("comfortably-active users never fetch on
        // startup"), but `A` is presence-only, so `autoRenewing = false` here also covers "never
        // written" — which is where every existing subscriber lands on their first run after this
        // ships. Declining means `A` is never written and the gate never revises its answer.
        //
        // If F2 rules for bootstrap-on-unknown this becomes a fetch and the test must change with it.
        assertNull(startupFetchReason(inDays(60), autoRenewing = false, now = now))
    }
}

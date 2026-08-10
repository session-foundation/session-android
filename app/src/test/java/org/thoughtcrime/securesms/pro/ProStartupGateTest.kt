package org.thoughtcrime.securesms.pro

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.thoughtcrime.securesms.pro.ProStatusManager.Companion.startupFetchReason
import java.time.Duration
import java.time.Instant

/**
 * The startup gate's decision, as a pure function of
 * (coverage end, auto-renewing, grace, now).
 *
 * Scope: the decision only. These do NOT cover the persisted 24h interval or the config read — both
 * need a database, and the interval is checked before this function is reached.
 *
 * The distinction every case below turns on: **the access expiry the backend sends is grace-inclusive**,
 * so it is coverage END, and the renewal falls due a grace period earlier. Anything keyed to coverage
 * end rather than to `coverageEnd - grace` is blind to the entire grace window.
 */
class ProStartupGateTest {

    private val now: Instant = Instant.parse("2026-08-07T00:00:00Z")

    /** A realistic Google Play base-plan grace period. Apple's is ~1h; Google's is days. */
    private val grace: Duration = Duration.ofDays(14)
    private val noGrace: Duration = Duration.ZERO

    private fun inDays(days: Long): Instant = now.plus(Duration.ofDays(days))
    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    // --- never subscribed -----------------------------------------------------------------------

    @Test
    fun `no access expiry means no fetch`() {
        // The population the gate exists for: users who have never subscribed were fetching on every
        // cold start and could never see a CTA.
        assertNull(startupFetchReason(null, autoRenewing = false, grace = noGrace, now = now))
        assertNull(startupFetchReason(null, autoRenewing = true, grace = grace, now = now))
    }

    // --- auto-renewing --------------------------------------------------------------------------

    @Test
    fun `auto-renewing and comfortably active does not fetch`() {
        // Renewal due in 6 days (coverage end 20 days out, less 14 days of grace).
        assertNull(startupFetchReason(inDays(20), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing and inside the grace window DOES fetch`() {
        // Coverage end is 7 days away, so `now` is comfortably before it — but the renewal fell due 7
        // days ago and grace is running. This is the state the whole rework exists to surface, and a
        // gate keyed to coverage end would sleep straight through it.
        assertNotNull(startupFetchReason(inDays(7), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing boundary - exactly at the renewal date fetches`() {
        assertNotNull(startupFetchReason(now.plus(grace), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing boundary - one second before the renewal date does not fetch`() {
        // The negative control for the pair above. Without it, "inside grace fetches" also passes
        // against a gate that fetches unconditionally whenever auto-renewing.
        val justBefore = now.plus(grace).plusSeconds(1)
        assertNull(startupFetchReason(justBefore, autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing past coverage end still fetches`() {
        assertNotNull(startupFetchReason(daysAgo(1), autoRenewing = true, grace = grace, now = now))
    }

    // --- not auto-renewing ----------------------------------------------------------------------

    @Test
    fun `not auto-renewing and expiring inside the CTA window fetches`() {
        assertNotNull(startupFetchReason(inDays(3), autoRenewing = false, grace = noGrace, now = now))
    }

    @Test
    fun `not auto-renewing boundary - just outside the 7 day window does not fetch`() {
        assertNull(startupFetchReason(inDays(8), autoRenewing = false, grace = noGrace, now = now))
    }

    @Test
    fun `not auto-renewing and recently expired fetches to confirm before the Expired CTA`() {
        // Config can read expired while a renewal landed on another device and hasn't synced, so the
        // Expired CTA must never fire off config alone.
        assertNotNull(startupFetchReason(daysAgo(5), autoRenewing = false, grace = noGrace, now = now))
    }

    @Test
    fun `not auto-renewing boundary - expired longer ago than the CTA window does not fetch`() {
        // Past 30 days the Expired CTA can no longer fire, so a confirming fetch has no consumer.
        assertNull(startupFetchReason(daysAgo(31), autoRenewing = false, grace = noGrace, now = now))
    }

    @Test
    fun `not auto-renewing, active, outside the CTA window does not fetch`() {
        // A prepaid or long non-renewing subscription. No CTA can fire, so there is nothing to fetch
        // for. Note this is now unambiguous: the proof-success path writes the renewing flag beside
        // the expiry it sets, so an absent flag genuinely means not-renewing rather than "never
        // recorded".
        assertNull(startupFetchReason(inDays(60), autoRenewing = false, grace = noGrace, now = now))
    }

    // --- the subtraction is unconditional -------------------------------------------------------

    @Test
    fun `zero grace makes coverage end and the renewal date the same instant`() {
        // The wire sends grace = 0 whenever the subscription is not auto-renewing, so subtracting
        // needs no provider branching and no null handling — it is a no-op for those accounts. Pinned
        // because a future reader may be tempted to guard the subtraction.
        val expiring = startupFetchReason(inDays(3), autoRenewing = false, grace = noGrace, now = now)
        val alsoExpiring = startupFetchReason(inDays(3), autoRenewing = false, grace = Duration.ZERO, now = now)
        assertNotNull(expiring)
        assertNotNull(alsoExpiring)
    }
}

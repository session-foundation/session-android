package org.thoughtcrime.securesms.pro

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.thoughtcrime.securesms.pro.ProStatusManager.Companion.startupFetchReason
import java.time.Duration
import java.time.Instant

/**
 * The startup gate's decision, as a pure function of (renewal due, auto-renewing, grace, now).
 *
 * Scope: the decision only. These do NOT cover the persisted 24h interval or the config read — both
 * need a database, and the interval is checked before this function is reached.
 *
 * Every case turns on the wire model stated in `toProStatus`: the expiry IS the payment-due date and
 * grace runs forward from it, so there is no subtraction anywhere and adding one double-counts. `the
 * auto-renewing bound is measured from coverage end, not the payment date` pins the direction.
 */
class ProStartupGateTest {

    private val now: Instant = Instant.parse("2026-08-07T00:00:00Z")

    /** Multi-day grace means an Apple account mid-dunning; Play's is only the ~1h latency allowance. */
    private val grace: Duration = Duration.ofDays(14)
    private val noGrace: Duration = Duration.ZERO

    private fun inDays(days: Long): Instant = now.plus(Duration.ofDays(days))
    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    // --- never subscribed -----------------------------------------------------------------------

    @Test
    fun `no access expiry means no fetch`() {
        // The population the gate exists for: no CTA can fire, so no fetch has a consumer.
        assertNull(startupFetchReason(null, autoRenewing = false, grace = noGrace, now = now))
        assertNull(startupFetchReason(null, autoRenewing = true, grace = grace, now = now))
    }

    // --- auto-renewing --------------------------------------------------------------------------

    @Test
    fun `auto-renewing and comfortably active does not fetch`() {
        assertNull(startupFetchReason(inDays(20), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing and inside the grace window DOES fetch`() {
        // Renewal due 7 days ago, grace 14: still covered, charge not landed. The state this exists for.
        assertNotNull(startupFetchReason(daysAgo(7), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing boundary - exactly at the renewal date fetches`() {
        assertNotNull(startupFetchReason(now, autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing boundary - one second before the renewal date does not fetch`() {
        // Negative control: without it, "inside grace fetches" also passes against a gate that always
        // fetches when auto-renewing.
        assertNull(startupFetchReason(now.plusSeconds(1), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing past coverage end still fetches`() {
        // Coverage ended 6 days ago: the renewal failed. Still fetches — this account is about to be
        // shown the Expired CTA, and config alone must never be the basis for that.
        assertNotNull(startupFetchReason(daysAgo(20), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing and long dead does not fetch`() {
        // Coverage ended 46 days ago, past the CTA window. Unbounded, an account whose renewing flag
        // was never cleared fetches on every cold start forever.
        assertNull(startupFetchReason(daysAgo(60), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `the auto-renewing bound is measured from coverage end, not the payment date`() {
        // Coverage ended 26 days ago, so the CTA can still fire. Discriminates the anchor: from the
        // payment date this reads as 40 days gone, past the window, and returns null. Only a grace
        // longer than the gap between the anchors tells them apart, hence the multi-day value.
        assertNotNull(startupFetchReason(daysAgo(40), autoRenewing = true, grace = grace, now = now))
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
        // A prepaid or long non-renewing subscription: no CTA can fire. Unambiguous because the
        // proof-success path writes the renewing flag beside the expiry, so absent means not-renewing
        // rather than never-recorded.
        assertNull(startupFetchReason(inDays(60), autoRenewing = false, grace = noGrace, now = now))
    }

    // --- grace's blast radius -------------------------------------------------------------------

    @Test
    fun `grace does not widen the non-renewing rows`() {
        // Grace belongs to one row, the auto-renewing one. The guard against it being reintroduced
        // into the others: the wire sends grace = 0 when not auto-renewing, so anything keyed to a
        // non-zero grace on this path only ever fires on a fixture.
        assertNull(startupFetchReason(daysAgo(31), autoRenewing = false, grace = grace, now = now))
        assertNotNull(startupFetchReason(inDays(3), autoRenewing = false, grace = grace, now = now))
    }
}

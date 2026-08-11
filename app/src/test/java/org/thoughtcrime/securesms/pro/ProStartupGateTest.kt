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
 * The model every case below turns on: **the access expiry the backend sends IS the payment-due date**,
 * and coverage runs a further grace period past it — the backend's own words are "`expiry_ts` +
 * `grace_period_duration` is exactly when we stop serving". So `expiry` needs no adjustment to get the
 * renewal date, and the grace window is `[expiry, expiry + grace)`.
 *
 * If you are here because you expected a subtraction: there isn't one, and adding it double-counts.
 * Grace runs FORWARD from the expiry. `the auto-renewing bound is measured from coverage end, not the
 * payment date` is the case that pins the direction.
 */
class ProStartupGateTest {

    private val now: Instant = Instant.parse("2026-08-07T00:00:00Z")

    /**
     * A realistic multi-day grace period, which on the wire means an Apple account mid-dunning: Apple
     * states its retry window separately, so it arrives as grace. Play folds its grace into the expiry
     * instead, so a Play account's grace is just the ~1h renewal-latency allowance.
     */
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
        // The renewal is 20 days out, so nothing can have gone wrong with it yet.
        assertNull(startupFetchReason(inDays(20), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing and inside the grace window DOES fetch`() {
        // The renewal fell due 7 days ago and grace runs 14, so coverage is still live but the charge
        // has not landed. This is the state the whole rework exists to surface.
        assertNotNull(startupFetchReason(daysAgo(7), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing boundary - exactly at the renewal date fetches`() {
        assertNotNull(startupFetchReason(now, autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing boundary - one second before the renewal date does not fetch`() {
        // The negative control for the pair above. Without it, "inside grace fetches" also passes
        // against a gate that fetches unconditionally whenever auto-renewing.
        assertNull(startupFetchReason(now.plusSeconds(1), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing past coverage end still fetches`() {
        // Renewal due 20 days ago, grace 14, so coverage ended 6 days ago: the renewal ultimately
        // failed. Still worth a fetch — this is the account about to be shown the Expired CTA, and
        // config alone must never be the basis for that.
        assertNotNull(startupFetchReason(daysAgo(20), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `auto-renewing and long dead does not fetch`() {
        // Renewal due 60 days ago, coverage ended 46 days ago, past the Expired CTA window. Without
        // this bound an account whose renewing flag was never cleared fetches on every cold start
        // forever.
        assertNull(startupFetchReason(daysAgo(60), autoRenewing = true, grace = grace, now = now))
    }

    @Test
    fun `the auto-renewing bound is measured from coverage end, not the payment date`() {
        // 40 days past the payment date with 14 days of grace: coverage ended 26 days ago, so the
        // Expired CTA can still fire and this must fetch.
        //
        // The discriminating case for where that bound is anchored. Measured from the payment date the
        // account reads as 40 days gone — past the 30 day window — and this returns null. Only the
        // coverage-end anchor gets it right, and only a grace period longer than the gap between the
        // two anchors can tell them apart, which is why this uses the multi-day Google value.
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
        // A prepaid or long non-renewing subscription. No CTA can fire, so there is nothing to fetch
        // for. Note this is now unambiguous: the proof-success path writes the renewing flag beside
        // the expiry it sets, so an absent flag genuinely means not-renewing rather than "never
        // recorded".
        assertNull(startupFetchReason(inDays(60), autoRenewing = false, grace = noGrace, now = now))
    }

    // --- grace's blast radius -------------------------------------------------------------------

    @Test
    fun `grace does not widen the non-renewing rows`() {
        // Grace belongs to ONE row — the auto-renewing one — and these pin that. A non-renewing
        // account 31 days past its expiry is out of CTA range whatever grace says, and one expiring in
        // 3 days is in range whatever grace says.
        //
        // This is the guard against grace being reintroduced into the other rows by someone who
        // remembers it mattering more. It cannot matter here: the wire sends grace = 0 when the
        // subscription is not auto-renewing, so any behaviour keyed to a non-zero grace on this path
        // is behaviour that never runs in production and only ever fires on a test fixture.
        assertNull(startupFetchReason(daysAgo(31), autoRenewing = false, grace = grace, now = now))
        assertNotNull(startupFetchReason(inDays(3), autoRenewing = false, grace = grace, now = now))
    }
}

package org.thoughtcrime.securesms.pro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.pro.ProStatusRepository.Companion.MIN_UPDATE_INTERVAL_SECONDS
import org.thoughtcrime.securesms.pro.ProStatusRepository.Companion.shouldFetch
import java.time.Instant

/**
 * The `get_pro_status` freshness floor.
 *
 * Scope, stated plainly: these cover the floor's **decision**, which is a pure function of
 * (immediate, last-fetch timestamp, now). They do NOT cover the wiring — that `requestRefresh`
 * reads the timestamp from `pro_state` rather than from `loadState`, and that a dropped request
 * really does skip `FetchProStatusWorker`. Both of those need WorkManager and a real database, so
 * they are not reachable from a JVM unit test here.
 *
 * That boundary matters because the wiring is where the original bug was: the floor asked whether
 * the in-memory load state was `Loading`/`Loaded`, and a cold start begins at `Init`, which is
 * neither — so the floor was skipped on precisely the path it existed for. What these tests pin is
 * the shape that prevents it recurring: the decision is expressed over the timestamp, and "no
 * timestamp" is a distinct, deliberate answer rather than a state that falls between the cases.
 */
class ProStatusFreshnessFloorTest {

    private val now: Instant = Instant.parse("2026-08-07T00:00:00Z")

    @Test
    fun `no recorded fetch means fetch`() {
        // A cold start with an empty pro_state, or a user who has never fetched. Distinct from the
        // old failure: this is "no evidence of a recent fetch", not "the state enum hasn't settled".
        assertTrue(shouldFetch(immediate = false, fetchedInThisProcess = true, lastFetchedAt = null, now = now))
    }

    @Test
    fun `a fetch inside the interval is dropped`() {
        val recent = now.minusSeconds(MIN_UPDATE_INTERVAL_SECONDS / 2)
        assertFalse(shouldFetch(immediate = false, fetchedInThisProcess = true, lastFetchedAt = recent, now = now))
    }

    @Test
    fun `a fetch older than the interval is allowed`() {
        val old = now.minusSeconds(MIN_UPDATE_INTERVAL_SECONDS + 1)
        assertTrue(shouldFetch(immediate = false, fetchedInThisProcess = true, lastFetchedAt = old, now = now))
    }

    @Test
    fun `the interval boundary is inclusive - exactly the interval ago is allowed`() {
        // Pinned deliberately: at exactly the floor the request goes through. The #4 grace poll
        // runs at exactly this cadence, so an exclusive boundary here would drop alternate ticks
        // to nothing but timing jitter.
        val exactly = now.minusSeconds(MIN_UPDATE_INTERVAL_SECONDS)
        assertTrue(shouldFetch(immediate = false, fetchedInThisProcess = true, lastFetchedAt = exactly, now = now))
    }

    @Test
    fun `immediate bypasses the floor even on a fetch that just happened`() {
        assertTrue(shouldFetch(immediate = true, fetchedInThisProcess = true, lastFetchedAt = now, now = now))
    }

    @Test
    fun `immediate is the only bypass - a just-completed fetch is otherwise dropped`() {
        // The negative control for the test above: same inputs, immediate off. Without this pair,
        // the immediate test passes just as well against a function that always returns true.
        assertFalse(shouldFetch(immediate = false, fetchedInThisProcess = true, lastFetchedAt = now, now = now))
    }

    @Test
    fun `the first request of a process is never floored`() {
        // Second exemption. A relaunch inside 60s of the last fetch would otherwise be dropped, and
        // several things downstream key off THIS process having confirmed the status rather than
        // off the stored value being recent — on Android, the home Expired CTA, which is gated on a
        // `Loading` transition that only a real fetch produces.
        assertTrue(
            shouldFetch(
                immediate = false,
                fetchedInThisProcess = false,
                lastFetchedAt = now.minusSeconds(1),
                now = now,
            )
        )
    }

    @Test
    fun `once this process has fetched the floor applies again`() {
        // Negative control for the exemption: same inputs, flag flipped. Without this the test
        // above passes against an exemption that never turns off.
        assertFalse(
            shouldFetch(
                immediate = false,
                fetchedInThisProcess = true,
                lastFetchedAt = now.minusSeconds(1),
                now = now,
            )
        )
    }

    @Test
    fun `a future timestamp still floors`() {
        // Clock skew, or a fetch recorded against network time while we compare against a slightly
        // behind reading. Treat it as fresh rather than fetching: the alternative reads a skewed
        // clock as a licence to bypass the floor entirely.
        val future = now.plusSeconds(MIN_UPDATE_INTERVAL_SECONDS)
        assertFalse(shouldFetch(immediate = false, fetchedInThisProcess = true, lastFetchedAt = future, now = now))
    }
}

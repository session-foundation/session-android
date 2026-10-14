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
 * Scope: the floor's decision, a pure function of (immediate, last-fetch timestamp, now). NOT the
 * wiring — that `requestRefresh` reads the timestamp from `pro_state` rather than `loadState`, and that
 * a dropped request really skips `FetchProStatusWorker`. Both need WorkManager and a database.
 *
 * What these pin is the shape: the decision is keyed off the timestamp, and "no timestamp" is a
 * deliberate answer rather than a value that falls between the cases.
 */
class ProStatusFreshnessFloorTest {

    private val now: Instant = Instant.parse("2026-08-07T00:00:00Z")

    @Test
    fun `no recorded fetch means fetch`() {
        // A cold start with an empty pro_state: no evidence of a recent fetch, which is a reason to go.
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
        // Inclusive on purpose: the #4 grace poll runs at exactly this cadence, so an exclusive
        // boundary would drop alternate ticks to timing jitter.
        val exactly = now.minusSeconds(MIN_UPDATE_INTERVAL_SECONDS)
        assertTrue(shouldFetch(immediate = false, fetchedInThisProcess = true, lastFetchedAt = exactly, now = now))
    }

    @Test
    fun `immediate bypasses the floor even on a fetch that just happened`() {
        assertTrue(shouldFetch(immediate = true, fetchedInThisProcess = true, lastFetchedAt = now, now = now))
    }

    @Test
    fun `immediate is the only bypass - a just-completed fetch is otherwise dropped`() {
        // Negative control: without it the immediate test passes against a function returning true.
        assertFalse(shouldFetch(immediate = false, fetchedInThisProcess = true, lastFetchedAt = now, now = now))
    }

    @Test
    fun `the first request of a process is never floored`() {
        // Second exemption: downstream consumers key off THIS process having confirmed the status, not
        // off the stored value being recent. See `ProStatusRepository.fetchedInThisProcess`.
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
        // Negative control: without it the test above passes against an exemption that never turns off.
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
        // Clock skew: a fetch recorded against network time compared with a slightly behind reading.
        // Treat as fresh — the alternative makes a skewed clock a licence to bypass the floor.
        val future = now.plusSeconds(MIN_UPDATE_INTERVAL_SECONDS)
        assertFalse(shouldFetch(immediate = false, fetchedInThisProcess = true, lastFetchedAt = future, now = now))
    }
}

package org.thoughtcrime.securesms.pro

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.pro.ProStatusManager.Companion.dropFirstProjection
import java.time.Instant

/**
 * Covers the guard on the access-expiry/prepaid config trigger.
 *
 * The bug it prevents: `distinctUntilChanged` is per-collection and has no baseline for its first
 * emission, so a projection of config the app ALREADY had was treated as a change and scheduled a
 * `get_pro_status` fetch on every cold launch — one second after the startup gate had declined, and
 * invisible to that gate because it is a different trigger. A never-subscribed account fetched on every
 * start.
 *
 * The two cases that matter are opposite risks, so both are asserted: the first projection must NOT fetch,
 * and a genuine later change — an `E` or prepaid marker synced from another device, which is the case this
 * trigger exists for — MUST still fetch.
 */
class ProConfigChangeTriggerTest {

    private val e1: Instant = Instant.parse("2026-08-15T20:23:28Z")
    private val e2: Instant = Instant.parse("2026-09-15T20:23:28Z")

    /** The projected pair the real flow carries: (access expiry, prepaid marker). */
    private fun projection(expiry: Instant?, prepaid: Long?) = expiry to prepaid

    @Test
    fun `the first projection does not fetch`() = runTest {
        // A cold launch on an account whose expiry is already in config. Nothing changed, so nothing
        // should be scheduled — this is the whole point of the guard.
        val emitted = flowOf(projection(e1, null)).dropFirstProjection().toList()

        assertEquals(emptyList<Pair<Instant?, Long?>>(), emitted)
    }

    @Test
    fun `a repeated projection does not fetch`() = runTest {
        // Unrelated profile edits re-emit the config flow with the same two values.
        val emitted = flowOf(
            projection(e1, null),
            projection(e1, null),
            projection(e1, null),
        ).dropFirstProjection().toList()

        assertEquals(emptyList<Pair<Instant?, Long?>>(), emitted)
    }

    @Test
    fun `an expiry synced from another device after the first projection does fetch`() = runTest {
        // The case the trigger exists for. The first emission establishes the baseline rather than being
        // acted on, so the real change behind it still gets through.
        val emitted = flowOf(
            projection(e1, null),
            projection(e2, null),
        ).dropFirstProjection().toList()

        assertEquals(listOf(projection(e2, null)), emitted)
    }

    @Test
    fun `a prepaid marker synced from another device does fetch`() = runTest {
        // Prepaid moves independently of the expiry: another device purchased and the entitlement has to
        // be pulled through even if that device goes offline before redeeming.
        val emitted = flowOf(
            projection(e1, null),
            projection(e1, 1_760_000_000L),
        ).dropFirstProjection().toList()

        assertEquals(listOf(projection(e1, 1_760_000_000L)), emitted)
    }

    @Test
    fun `only the changes are emitted, not the noise between them`() = runTest {
        val emitted = flowOf(
            projection(e1, null),   // first projection — baseline
            projection(e1, null),   // unrelated profile edit
            projection(e2, null),   // real change
            projection(e2, null),   // unrelated profile edit
            projection(e2, 42L),    // real change
        ).dropFirstProjection().toList()

        assertEquals(listOf(projection(e2, null), projection(e2, 42L)), emitted)
    }

    @Test
    fun `a change arriving as the very first emission is swallowed`() = runTest {
        // Documenting the accepted cost rather than pretending it away: with no baseline there is no way
        // to tell a change from a projection, so the first emission is always treated as a projection.
        // Harmless on a brand-new account, which has no Pro to lose, and it is the same trade iOS makes
        // with `hasProjectedUserConfig`. If a real transition is ever seen to land as the first emission
        // on an EXISTING account, this test is the thing to revisit.
        val emitted = flowOf(projection(e2, null)).dropFirstProjection().toList()

        assertEquals(emptyList<Pair<Instant?, Long?>>(), emitted)
    }
}

package org.thoughtcrime.securesms.pro

import network.loki.messenger.libsession_util.protocol.ProMessageFeature
import network.loki.messenger.libsession_util.protocol.ProProfileFeature
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "sent" Pro stats as running totals.
 *
 * Note what is NOT tested here, because it is no longer expressible: that deleting a sent message does not
 * reduce the stat. The message table is not an input to this code at all, so there is no seam through which
 * a deletion could act. That is a stronger position than a test — the old implementation needed one because
 * it counted rows on every read.
 */
class ProSendStatsTest {

    /** Stands in for `ProDatabase`'s pro_state table. */
    private class FakeStore : ProSendStats.Store {
        val values = mutableMapOf<String, Long>()
        var writes = 0

        override fun getProStatCount(name: String): Long? = values[name]

        override fun incrementProStatCount(name: String) {
            writes++
            values[name] = (values[name] ?: 0L) + 1L
        }
    }

    private val store = FakeStore()

    // --- a qualifying send increments -----------------------------------------------------------

    @Test
    fun `a sent long message increments the long-message total`() {
        val feature = ProMessageFeature.HIGHER_CHARACTER_LIMIT

        ProSendStats.recordSendSuccess(store, wasAlreadySent = false, features = setOf(feature))

        assertEquals(1, ProSendStats.total(store, feature))
    }

    @Test
    fun `a sent pro badge increments the badge total`() {
        val feature = ProProfileFeature.PRO_BADGE

        ProSendStats.recordSendSuccess(store, wasAlreadySent = false, features = setOf(feature))

        assertEquals(1, ProSendStats.total(store, feature))
    }

    @Test
    fun `each stat counts only its own feature`() {
        // One message carrying both features feeds both stats, and neither leaks into the other.
        ProSendStats.recordSendSuccess(
            store,
            wasAlreadySent = false,
            features = setOf(ProMessageFeature.HIGHER_CHARACTER_LIMIT, ProProfileFeature.PRO_BADGE)
        )
        ProSendStats.recordSendSuccess(
            store,
            wasAlreadySent = false,
            features = setOf(ProMessageFeature.HIGHER_CHARACTER_LIMIT)
        )

        assertEquals(2, ProSendStats.total(store, ProMessageFeature.HIGHER_CHARACTER_LIMIT))
        assertEquals(1, ProSendStats.total(store, ProProfileFeature.PRO_BADGE))
    }

    @Test
    fun `a send carrying no counted feature changes nothing`() {
        ProSendStats.recordSendSuccess(store, wasAlreadySent = false, features = emptySet())

        assertEquals(0, store.writes)
        assertEquals(0, ProSendStats.total(store, ProMessageFeature.HIGHER_CHARACTER_LIMIT))
    }

    @Test
    fun `a stat nothing has fed reads zero rather than failing`() {
        assertEquals(0, ProSendStats.total(store, ProProfileFeature.PRO_BADGE))
    }

    // --- the totals are historic ----------------------------------------------------------------

    @Test
    fun `later sends without the badge do not reduce the historic badge count`() {
        // The count is of badges SENT, not of the badge being on now. Switching Pro Badges off stops new
        // messages carrying one -- it does not un-send the ones that did.
        //
        // Pinned because the conflation is easy to make in a later edit: "do not attach a badge to this
        // message" and "do not count it" are one thought away from each other.
        ProSendStats.recordSendSuccess(
            store,
            wasAlreadySent = false,
            features = setOf(ProProfileFeature.PRO_BADGE)
        )

        repeat(3) {
            ProSendStats.recordSendSuccess(
                store,
                wasAlreadySent = false,
                features = setOf(ProMessageFeature.HIGHER_CHARACTER_LIMIT),
            )
        }

        assertEquals(1, ProSendStats.total(store, ProProfileFeature.PRO_BADGE))
    }

    // --- one contribution per message, on its first confirmed send ------------------------------

    @Test
    fun `a repeat success for an already-sent message does not count again`() {
        val feature = ProMessageFeature.HIGHER_CHARACTER_LIMIT

        ProSendStats.recordSendSuccess(store, wasAlreadySent = false, features = setOf(feature))
        ProSendStats.recordSendSuccess(store, wasAlreadySent = true, features = setOf(feature))
        ProSendStats.recordSendSuccess(store, wasAlreadySent = true, features = setOf(feature))

        assertEquals(1, ProSendStats.total(store, feature))
    }

    @Test
    fun `a retry after a failed attempt counts once, not twice`() {
        // The attempt that failed never reached "sent", so it never reported. The retry transitions out
        // of failed -- not out of sent -- so it is that message's first success and counts once.
        val feature = ProProfileFeature.PRO_BADGE

        ProSendStats.recordSendSuccess(store, wasAlreadySent = false, features = setOf(feature))

        assertEquals(1, ProSendStats.total(store, feature))
    }

    @Test
    fun `an already-sent message contributes nothing even to a stat it has never fed`() {
        // Guards the gate rather than the arithmetic: it must short-circuit before touching the store, so
        // a repeat cannot create a counter that was never incremented.
        ProSendStats.recordSendSuccess(
            store,
            wasAlreadySent = true,
            features = setOf(ProProfileFeature.PRO_BADGE)
        )

        assertEquals(0, store.writes)
    }
}

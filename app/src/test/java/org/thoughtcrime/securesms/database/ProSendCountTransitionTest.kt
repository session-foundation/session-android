package org.thoughtcrime.securesms.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which message states already count as "sent", which is what decides whether marking a message sent adds
 * to the Pro "sent" stats.
 *
 * The trap this exists to pin: a 1:1 message does NOT stay in SENT after a successful send. It is moved
 * straight to SYNCING so a copy can go to our own devices, and that sync leg reports success through the
 * same path — so testing SENT alone counts every 1:1 message twice. The sync states have to count as
 * already-sent, because nothing starts syncing a message that never went out.
 *
 * Lives in this package to reach the base type constants.
 */
class ProSendCountTransitionTest {

    private fun hasBeenSent(baseType: Long) =
        MmsSmsColumns.Types.hasBeenSentSuccessfully(baseType)

    // --- states that mean "not yet sent": marking sent counts ------------------------------------

    @Test
    fun `a message still sending has not been sent`() {
        assertFalse(hasBeenSent(MmsSmsColumns.Types.BASE_SENDING_TYPE))
    }

    @Test
    fun `a failed message has not been sent, so its retry counts`() {
        // The attempt that failed never reached the recipient, so the retry is the first success.
        assertFalse(hasBeenSent(MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE))
    }

    @Test
    fun `an outbox message has not been sent`() {
        assertFalse(hasBeenSent(MmsSmsColumns.Types.BASE_OUTBOX_TYPE))
    }

    // --- states that mean "already sent": marking sent must NOT count again ----------------------

    @Test
    fun `a sent message has been sent`() {
        assertTrue(hasBeenSent(MmsSmsColumns.Types.BASE_SENT_TYPE))
    }

    @Test
    fun `a syncing message has already been sent`() {
        // The ordinary 1:1 path: send succeeds, the row moves to SYNCING, and the sync leg then reports
        // success through the same path. Reading this as not-yet-sent double-counts every 1:1 message.
        assertTrue(hasBeenSent(MmsSmsColumns.Types.BASE_SYNCING_TYPE))
    }

    @Test
    fun `a resyncing message has already been sent`() {
        assertTrue(hasBeenSent(MmsSmsColumns.Types.BASE_RESYNCING_TYPE))
    }

    @Test
    fun `a sync-failed message has already been sent`() {
        // Only the sync to our own devices failed; the recipient got it, and it was counted then.
        assertTrue(hasBeenSent(MmsSmsColumns.Types.BASE_SYNC_FAILED_TYPE))
    }

    // --- the whole outgoing lifecycle, in order -------------------------------------------------

    @Test
    fun `a 1 to 1 message counts exactly once across send then sync`() {
        // Walk the real sequence and count how many transitions into "sent" would report.
        val lifecycle = listOf(
            MmsSmsColumns.Types.BASE_SENDING_TYPE,   // inserted, sending        -> reports
            MmsSmsColumns.Types.BASE_SYNCING_TYPE,   // sent, now syncing        -> must not report
        )

        assertTrue(lifecycle.count { !hasBeenSent(it) } == 1)
    }

    @Test
    fun `a retried then synced message also counts exactly once`() {
        val lifecycle = listOf(
            MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE, // retry after failure    -> reports
            MmsSmsColumns.Types.BASE_SYNCING_TYPE,     // then syncs             -> must not report
            MmsSmsColumns.Types.BASE_SYNC_FAILED_TYPE, // sync failed, resync    -> must not report
        )

        assertTrue(lifecycle.count { !hasBeenSent(it) } == 1)
    }
}

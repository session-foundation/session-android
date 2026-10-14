package org.thoughtcrime.securesms.database

import network.loki.messenger.libsession_util.protocol.ProMessageFeature
import network.loki.messenger.libsession_util.protocol.ProProfileFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.pro.toProMessageBitSetValue
import org.thoughtcrime.securesms.pro.toProProfileBitSetValue

/**
 * The row-level decision behind the Pro "sent" stats: whether a row can contribute at all, whether it
 * already has, and which stats it feeds.
 *
 * This is the seam the SMS and MMS tables share. Each supplies its own cursor, but if this drifted the
 * symptom would be a stat that is correct for text messages and wrong for media, or the reverse — which is
 * exactly the kind of split that survives a casual test.
 */
class ProSendStatRowTest {

    private val longMessageMask = setOf(ProMessageFeature.HIGHER_CHARACTER_LIMIT).toProMessageBitSetValue()
    private val badgeMask = setOf(ProProfileFeature.PRO_BADGE).toProProfileBitSetValue()

    // --- only outgoing rows can contribute -------------------------------------------------------

    @Test
    fun `an incoming row cannot contribute`() {
        // It carries the SENDER's feature bits. Counting it would add someone else's badge to our total.
        assertNull(
            ProSendStatRow.from(
                type = MmsSmsColumns.Types.BASE_INBOX_TYPE,
                messageFeatures = longMessageMask,
                profileFeatures = badgeMask,
            )
        )
    }

    @Test
    fun `an outgoing row contributes`() {
        assertTrue(
            ProSendStatRow.from(MmsSmsColumns.Types.BASE_SENDING_TYPE, longMessageMask, 0L) != null
        )
    }

    // --- whether it has already been sent --------------------------------------------------------

    @Test
    fun `a sending row has not already been sent`() {
        val state = ProSendStatRow.from(MmsSmsColumns.Types.BASE_SENDING_TYPE, longMessageMask, 0L)
        assertFalse(state!!.wasAlreadySent)
    }

    @Test
    fun `a syncing row has already been sent`() {
        // The 1:1 path moves a just-sent message here, and the sync leg reports success the same way.
        val state = ProSendStatRow.from(MmsSmsColumns.Types.BASE_SYNCING_TYPE, longMessageMask, 0L)
        assertTrue(state!!.wasAlreadySent)
    }

    @Test
    fun `a failed row has not already been sent, so its retry counts`() {
        val state = ProSendStatRow.from(MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE, longMessageMask, 0L)
        assertFalse(state!!.wasAlreadySent)
    }

    // --- which stats the row feeds ---------------------------------------------------------------

    @Test
    fun `a message-feature bit is decoded`() {
        val state = ProSendStatRow.from(MmsSmsColumns.Types.BASE_SENDING_TYPE, longMessageMask, 0L)
        assertEquals(setOf(ProMessageFeature.HIGHER_CHARACTER_LIMIT), state!!.features)
    }

    @Test
    fun `a profile-feature bit is decoded`() {
        val state = ProSendStatRow.from(MmsSmsColumns.Types.BASE_SENDING_TYPE, 0L, badgeMask)
        assertEquals(setOf(ProProfileFeature.PRO_BADGE), state!!.features)
    }

    @Test
    fun `both columns are read, not just one`() {
        // The trap: reading only the message column silently drops every badge, and the stat reads zero
        // forever with nothing failing.
        val state = ProSendStatRow.from(MmsSmsColumns.Types.BASE_SENDING_TYPE, longMessageMask, badgeMask)

        assertEquals(
            setOf(ProMessageFeature.HIGHER_CHARACTER_LIMIT, ProProfileFeature.PRO_BADGE),
            state!!.features
        )
    }

    @Test
    fun `a row with no feature bits contributes nothing to count`() {
        val state = ProSendStatRow.from(MmsSmsColumns.Types.BASE_SENDING_TYPE, 0L, 0L)

        assertTrue(state!!.features.isEmpty())
    }
}

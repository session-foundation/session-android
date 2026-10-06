package org.session.libsession.messaging.sending_receiving

import org.junit.Assert.assertEquals
import org.junit.Test
import org.session.libsession.messaging.sending_receiving.UnsupportedMessageBanner.REAPPEAR_AFTER_DISMISSAL_MS
import org.session.libsession.messaging.sending_receiving.UnsupportedMessageBanner.State

class UnsupportedMessageBannerTest {

    private fun state(triggered: Long?, otherDevice: Long? = null, dismissed: Long? = null) =
        UnsupportedMessageBanner.state(triggeredAtMs = triggered, otherDeviceTriggeredAtMs = otherDevice, dismissedAtMs = dismissed)

    @Test
    fun `hidden until triggered`() {
        assertEquals(State.HIDDEN, state(triggered = null))
        assertEquals(State.HIDDEN, state(triggered = null, dismissed = 1_000L))
    }

    @Test
    fun `general text when only a newer format triggered it and it was never dismissed`() {
        // A missing threshold must not make a missing other-device trigger count as current
        assertEquals(State.GENERAL, state(triggered = 1_000L))
    }

    @Test
    fun `other device text wins when that was a trigger`() {
        assertEquals(State.OTHER_DEVICE, state(triggered = 2_000L, otherDevice = 1_000L))
    }

    @Test
    fun `only reappears for a trigger at least a week after dismissal`() {
        val dismissed = 10_000L

        assertEquals(State.HIDDEN, state(triggered = dismissed + REAPPEAR_AFTER_DISMISSAL_MS - 1, dismissed = dismissed))
        assertEquals(State.GENERAL, state(triggered = dismissed + REAPPEAR_AFTER_DISMISSAL_MS, dismissed = dismissed))
    }

    @Test
    fun `an other device trigger from before the dismissal no longer picks its text`() {
        val dismissed = 10_000L
        val triggered = dismissed + REAPPEAR_AFTER_DISMISSAL_MS

        assertEquals(State.GENERAL, state(triggered = triggered, otherDevice = dismissed - 1, dismissed = dismissed))
        assertEquals(State.OTHER_DEVICE, state(triggered = triggered, otherDevice = triggered, dismissed = dismissed))
    }

    @Test
    fun `a dismissal after the last trigger hides it`() {
        assertEquals(State.HIDDEN, state(triggered = 1_000L, otherDevice = 1_000L, dismissed = 2_000L))
    }
}

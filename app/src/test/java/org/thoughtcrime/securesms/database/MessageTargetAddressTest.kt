package org.thoughtcrime.securesms.database

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.AccountId

/**
 * Which address a message is stored against, and therefore which stored message a later copy of it is
 * compared with.
 *
 * Both message tables reject a duplicate on `DATE_SENT`, `ADDRESS` and `THREAD_ID`. The first and third
 * are the same for a message and its echo by construction, so this decision is the whole of the check —
 * a conversation type that answers with the sender here has no duplicate protection at all once the
 * in-memory guard is gone, which is what a restart does to it.
 */
@RunWith(RobolectricTestRunner::class)
class MessageTargetAddressTest {

    private val sender = Address.Standard(
        AccountId("0538e63512fd78c04d45b83ec7f0f3d593f60276ce535d1160eb589a00cca7db59")
    )
    private val community = Address.Community(serverUrl = "https://open.getsession.org", room = "session")
    private val group = Address.Group(
        AccountId("0338e63512fd78c04d45b83ec7f0f3d593f60276ce535d1160eb589a00cca7db59")
    )

    @Test
    fun `a community message is stored against the community`() {
        // The regression this exists for. Answering with the sender stored our own message a second time,
        // because the copy saved when it was composed is addressed to the community.
        assertEquals(community, MessageTargetAddress.of(threadAddress = community, senderAddress = sender))
    }

    @Test
    fun `a group message is stored against the group`() {
        assertEquals(group, MessageTargetAddress.of(threadAddress = group, senderAddress = sender))
    }

    @Test
    fun `a one to one message is stored against the sender`() {
        val other = Address.Standard(
            AccountId("0578e63512fd78c04d45b83ec7f0f3d593f60276ce535d1160eb589a00cca7db59")
        )
        assertEquals(sender, MessageTargetAddress.of(threadAddress = other, senderAddress = sender))
    }

    @Test
    fun `a legacy group message is still stored against the sender`() {
        // Not an oversight. LegacyGroup is GroupLike, so widening the check to that interface rather than
        // naming the two supported types would silently change it, and legacy groups are unsupported.
        val legacy = Address.LegacyGroup("ab0123")
        assertEquals(sender, MessageTargetAddress.of(threadAddress = legacy, senderAddress = sender))
    }
}

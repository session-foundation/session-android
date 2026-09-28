package org.thoughtcrime.securesms.dependencies

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import network.loki.messenger.libsession_util.MutableGroupKeysConfig
import org.junit.Test
import org.session.libsession.utilities.ConfigMessage
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How many keys messages a merge took in.
 *
 * `loadKey`'s three outcomes are libsession's: true for a message that gave this device a key, false for
 * a valid one that did not, and a throw for one it rejected. [SupplementalKeysLoadTest] asserts the false
 * case against the real library. These cover the counting on top of it, which a JVM test can reach.
 */
class LoadKeyMessagesTest {

    private val full = ConfigMessage("full-1", "full".toByteArray(), 1L)
    private val supplemental = ConfigMessage("supplemental-1", "supplemental".toByteArray(), 2L)

    @Test
    fun `a message that gives us no key is still taken in`() {
        val keys = keysAnswering(full to true, supplemental to false)

        val load = keys.loadKeyMessages(listOf(full, supplemental))

        assertEquals(2, load.takenIn)
        assertTrue(load.gaveUsAKey)
    }

    @Test
    fun `messages that give us no key change nothing, but are all taken in`() {
        val keys = keysAnswering(supplemental to false)

        val load = keys.loadKeyMessages(listOf(supplemental))

        assertEquals(1, load.takenIn)
        assertFalse(load.gaveUsAKey)
    }

    /** A message that gives us a key must not stop the ones after it from being loaded. */
    @Test
    fun `every message is loaded`() {
        val keys = keysAnswering(full to true, supplemental to false)

        keys.loadKeyMessages(listOf(full, supplemental))

        verify(exactly = 1) { keys.loadKey(supplemental.data, supplemental.hash, supplemental.timestamp) }
    }

    /** A rejected message is not counted as taken in, and nothing is: the merge fails as a whole. */
    @Test
    fun `a rejected message reaches the caller`() {
        val keys = keysAnswering(full to true)
        every { keys.loadKey(supplemental.data, supplemental.hash, supplemental.timestamp) } throws
                RuntimeException("libsession: C++ exception: Invalid supplemental key message")

        assertFailsWith<RuntimeException> { keys.loadKeyMessages(listOf(full, supplemental)) }
    }

    private fun keysAnswering(vararg answers: Pair<ConfigMessage, Boolean>) =
        mockk<MutableGroupKeysConfig>().also { keys ->
            for ((message, answer) in answers) {
                every { keys.loadKey(message.data, message.hash, message.timestamp) } returns answer
            }
        }
}

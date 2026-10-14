package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ConfigTtlExtensionThrottleTest {

    private val userSwarm = "05" + "a".repeat(64)
    private val groupSwarm = "03" + "b".repeat(64)
    private val otherGroupSwarm = "03" + "c".repeat(64)

    private val time = TestTimeSource()
    private val throttle = ConfigTtlExtensionThrottle(time)

    private var extensionsSent = 0
    private val succeed: suspend () -> Unit = { extensionsSent++ }
    private val fail: suspend () -> Unit = {
        extensionsSent++
        throw RuntimeException("storage server rejected the extension")
    }

    @Test
    fun `two polls inside the window send one extension`() = runTest {
        assertTrue(throttle.extendIfDue(userSwarm, succeed))
        time += 30.minutes
        assertFalse(throttle.extendIfDue(userSwarm, succeed))

        assertEquals(1, extensionsSent)
    }

    @Test
    fun `the window is still closed just before an hour`() = runTest {
        throttle.extendIfDue(userSwarm, succeed)
        time += ConfigTtlExtensionThrottle.COOLDOWN - 1.seconds

        assertFalse(throttle.extendIfDue(userSwarm, succeed))
        assertEquals(1, extensionsSent)
    }

    @Test
    fun `a poll after the window sends another extension`() = runTest {
        throttle.extendIfDue(userSwarm, succeed)
        time += ConfigTtlExtensionThrottle.COOLDOWN

        assertTrue(throttle.extendIfDue(userSwarm, succeed))
        assertEquals(2, extensionsSent)
    }

    @Test
    fun `a failed extension does not start the cooldown`() = runTest {
        assertFailsWith<RuntimeException> { throttle.extendIfDue(userSwarm, fail) }
        time += 1.seconds

        assertTrue(throttle.extendIfDue(userSwarm, succeed))
        assertEquals(2, extensionsSent)

        // Positive control: the success above did start the cooldown, so the retry is observable as a
        // retry rather than as a throttle that never engages.
        assertFalse(throttle.extendIfDue(userSwarm, succeed))
        assertEquals(2, extensionsSent)
    }

    @Test
    fun `repeated failures keep retrying on every poll`() = runTest {
        repeat(3) {
            assertFailsWith<RuntimeException> { throttle.extendIfDue(userSwarm, fail) }
            time += 10.seconds
        }

        assertEquals(3, extensionsSent)
    }

    @Test
    fun `a failure after the window re-opens it does not extend the old cooldown`() = runTest {
        throttle.extendIfDue(userSwarm, succeed)
        time += ConfigTtlExtensionThrottle.COOLDOWN

        assertFailsWith<RuntimeException> { throttle.extendIfDue(userSwarm, fail) }
        time += 1.seconds

        assertTrue(throttle.extendIfDue(userSwarm, succeed))
        assertEquals(3, extensionsSent)
    }

    @Test
    fun `a cancelled extension does not start the cooldown`() = runTest {
        assertFailsWith<CancellationException> {
            throttle.extendIfDue(userSwarm) { throw CancellationException("poller stopped") }
        }

        assertTrue(throttle.extendIfDue(userSwarm, succeed))
        assertEquals(1, extensionsSent)
    }

    @Test
    fun `each swarm has its own cooldown`() = runTest {
        throttle.extendIfDue(groupSwarm, succeed)

        assertTrue(throttle.extendIfDue(otherGroupSwarm, succeed))
        assertTrue(throttle.extendIfDue(userSwarm, succeed))
        assertFalse(throttle.extendIfDue(groupSwarm, succeed))
        assertEquals(3, extensionsSent)
    }
}

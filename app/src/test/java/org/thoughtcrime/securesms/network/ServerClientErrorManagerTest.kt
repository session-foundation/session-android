package org.thoughtcrime.securesms.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.session.libsession.network.ServerClientErrorManager
import org.session.libsession.network.ServerClientFailureContext
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.model.ErrorStatus
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.thoughtcrime.securesms.LogMockingTestBase

class ServerClientErrorManagerTest: LogMockingTestBase() {

    private val snodeClock = mock<SnodeClock>()
    private val manager = ServerClientErrorManager(snodeClock = snodeClock)

    private fun serverDest() = OnionDestination.ServerDestination(
        host = "example.com",
        target = "v4",
        x25519PublicKey = "xkey",
        scheme = "https",
        port = 443
    )

    @Test
    fun `COS 425 first time - resync true to Retry`() = runTest {
        whenever(snodeClock.resyncClock()).thenReturn(true)

        val status = ErrorStatus(code = 425, message = "Clock out of sync", body = null)
        val error = OnionError.DestinationError(destination = serverDest(), status = status)

        val decision = manager.onFailure(
            error = error,
            ctx = ServerClientFailureContext(url = "https://example.com", previousError = null)
        )

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(snodeClock).resyncClock()
    }

    @Test
    fun `COS 425 first time - resync false to Fail`() = runTest {
        whenever(snodeClock.resyncClock()).thenReturn(false)

        val status = ErrorStatus(code = 425, message = "Clock out of sync", body = null)
        val error = OnionError.DestinationError(destination = serverDest(), status = status)

        val decision = manager.onFailure(
            error = error,
            ctx = ServerClientFailureContext(url = "https://example.com", previousError = null)
        )

        assertThat(decision).isInstanceOf(FailureDecision.Fail::class.java)
        verify(snodeClock).resyncClock()
    }

    @Test
    fun `COS 425 second time to Fail (no more remediation)`() = runTest {
        val status = ErrorStatus(code = 425, message = "Clock out of sync", body = null)
        val error = OnionError.DestinationError(destination = serverDest(), status = status)

        val decision = manager.onFailure(
            error = error,
            ctx = ServerClientFailureContext(url = "https://example.com", previousError = error)
        )

        assertThat(decision).isInstanceOf(FailureDecision.Fail::class.java)
        verify(snodeClock, never()).resyncClock()
    }

    @Test
    fun `default - non COS DestinationError to Fail`() = runTest {
        val status = ErrorStatus(code = 500, message = "nope", body = null)
        val error = OnionError.DestinationError(destination = serverDest(), status = status)

        val decision = manager.onFailure(
            error = error,
            ctx = ServerClientFailureContext(url = "https://example.com", previousError = null)
        )

        assertThat(decision).isInstanceOf(FailureDecision.Fail::class.java)
    }
}

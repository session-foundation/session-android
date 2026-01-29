package org.thoughtcrime.securesms.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.model.FailureDecision
import org.thoughtcrime.securesms.api.server.ServerApiErrorManager
import org.thoughtcrime.securesms.api.server.ServerClientFailureContext
import org.thoughtcrime.securesms.util.MockLoggingRule

class ServerApiErrorManagerTest {

    @get:Rule
    val logRule = MockLoggingRule()

    private val snodeClock = mock<SnodeClock>()
    private val manager = ServerApiErrorManager(snodeClock = snodeClock)

    @Test
    fun `COS 425 first time - resync true to Retry`() = runTest {
        whenever(snodeClock.resyncClock()).thenReturn(true)

        val (_, decision) = manager.onFailure(
            ctx = ServerClientFailureContext(previousErrorCode = null),
            errorCode = 425,
            bodyAsText = null,
            serverBaseUrl = ""
        )

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(snodeClock).resyncClock()
    }

    @Test
    fun `COS 425 first time - resync false to Fail`() = runTest {
        whenever(snodeClock.resyncClock()).thenReturn(false)

        val (_, decision) = manager.onFailure(
            ctx = ServerClientFailureContext(previousErrorCode = null),
            errorCode = 425,
            bodyAsText = null,
            serverBaseUrl = ""
        )

        assertThat(decision).isInstanceOf(FailureDecision.Fail::class.java)
        verify(snodeClock).resyncClock()
    }

    @Test
    fun `COS 425 second time to Fail (no more remediation)`() = runTest {
        val (_, decision) = manager.onFailure(
            ctx = ServerClientFailureContext(previousErrorCode = 425),
            errorCode = 425,
            bodyAsText = null,
            serverBaseUrl = ""
        )

        assertThat(decision).isInstanceOf(FailureDecision.Fail::class.java)
        verify(snodeClock, never()).resyncClock()
    }

    @Test
    fun `default - non COS DestinationError to Fail`() = runTest {
        val (_, decision) = manager.onFailure(
            ctx = ServerClientFailureContext(previousErrorCode = null),
            errorCode = 500,
            bodyAsText = null,
            serverBaseUrl = ""
        )

        assertThat(decision).isEqualTo(null)
    }
}

package org.thoughtcrime.securesms.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.onion.PathManager
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.SnodeApiErrorManager
import org.thoughtcrime.securesms.api.snode.SnodeClientFailureContext
import org.thoughtcrime.securesms.util.MockLoggingRule

class SnodeApiErrorManagerTest {

    @get:Rule
    val logRule = MockLoggingRule()

    private val pathManager = mock<PathManager>()
    private val snodeClock = mock<SnodeClock>()

    private val manager = SnodeApiErrorManager(
        pathManager = pathManager,
        snodeClock = snodeClock
    )

    private fun snode(id: String) =
        Snode(
            address = "https://$id.example",
            port = 443,
            publicKeySet = Snode.KeySet(ed25519Key = "ed_$id", x25519Key = "x_$id"),
        )

    @Test
    fun `COS 406 first time - resync true to Retry`() = runTest {
        val target = snode("target")
        whenever(snodeClock.resyncClock()).thenReturn(true)

        val (_, decision) = manager.onFailure(
            snode = target,
            errorCode = 406,
            bodyText = null,
            ctx = SnodeClientFailureContext()
        )

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(snodeClock).resyncClock()
        verifyNoInteractions(pathManager)
    }

    @Test
    fun `COS 406 first time - resync false to Fail`() = runTest {
        val target = snode("target")
        whenever(snodeClock.resyncClock()).thenReturn(false)

        val (_, decision) = manager.onFailure(
            snode = target,
            errorCode = 406,
            bodyText = null,
            ctx = SnodeClientFailureContext()
        )

        assertThat(decision).isInstanceOf(FailureDecision.Fail::class.java)
        verify(snodeClock).resyncClock()
        verifyNoInteractions(pathManager)
    }

    @Test
    fun `COS 406 second time to forceRemove target snode and Retry`() = runTest {
        val target = snode("target")

        val (_, decision) = manager.onFailure(
            snode = target,
            errorCode = 406,
            bodyText = null,
            ctx = SnodeClientFailureContext(previousErrorCode = 406)
        )

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(pathManager).handleBadSnode(snode = target, forceRemove = true)
        verify(snodeClock, never()).resyncClock()
    }

    @Test
    fun `502 unparsable data to forceRemove target snode and Retry`() = runTest {
        val target = snode("target")

        val (_, decision) = manager.onFailure(
            snode = target,
            errorCode = 502,
            bodyText = "oxend returned unparsable data",
            ctx = SnodeClientFailureContext()
        )

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(pathManager).handleBadSnode(snode = target, forceRemove = true)
    }
}

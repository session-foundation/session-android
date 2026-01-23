package org.thoughtcrime.securesms.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.session.libsession.network.SnodeClientErrorManager
import org.session.libsession.network.SnodeClientFailureContext
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.model.ErrorStatus
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.util.MockLoggingRule

class SnodeClientErrorManagerTest {

    @get:Rule
    val logRule = MockLoggingRule()

    private val pathManager = mock<PathManager>()
    private val swarmDirectory = mock<SwarmDirectory>()
    private val snodeClock = mock<SnodeClock>()

    private val manager = SnodeClientErrorManager(
        pathManager = pathManager,
        swarmDirectory = swarmDirectory,
        snodeClock = snodeClock
    )

    private fun snode(id: String) =
        Snode(
            address = "https://$id.example",
            port = 443,
            publicKeySet = Snode.KeySet(ed25519Key = "ed_$id", x25519Key = "x_$id"),
        )

    private fun snodeDest(s: Snode) = OnionDestination.SnodeDestination(s)

    @Test
    fun `DestinationUnreachable to forceRemove target snode and Retry`() = runTest {
        val target = snode("target")
        val dest = snodeDest(target)
        val status = ErrorStatus(code = 502, message = "nope", body = null)

        val error = OnionError.DestinationUnreachable(status = status, destination = dest)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = "pub")

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(pathManager).handleBadSnode(snode = target, forceRemove = true)
    }

    @Test
    fun `COS 406 first time - resync true to Retry`() = runTest {
        val target = snode("target")
        whenever(snodeClock.resyncClock()).thenReturn(true)

        val status = ErrorStatus(code = 406, message = "Clock out of sync", body = null)
        val error = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = "pub", previousError = null)

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(snodeClock).resyncClock()
        verifyNoInteractions(pathManager)
    }

    @Test
    fun `COS 406 first time - resync false to Fail`() = runTest {
        val target = snode("target")
        whenever(snodeClock.resyncClock()).thenReturn(false)

        val status = ErrorStatus(code = 406, message = "Clock out of sync", body = null)
        val error = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = "pub", previousError = null)

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isInstanceOf(FailureDecision.Fail::class.java)
        verify(snodeClock).resyncClock()
        verifyNoInteractions(pathManager)
    }

    @Test
    fun `COS 406 second time to forceRemove target snode and Retry`() = runTest {
        val target = snode("target")

        val status = ErrorStatus(code = 406, message = "Clock out of sync", body = null)
        val error = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val previous = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = "pub", previousError = previous)

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(pathManager).handleBadSnode(snode = target, forceRemove = true)
        verify(snodeClock, never()).resyncClock()
    }

    @Test
    fun `421 with pubKey - updateSwarmFromResponse true to Retry and no drop`() = runTest {
        val target = snode("target")
        whenever(swarmDirectory.updateSwarmFromResponse(eq("pub"), any())).thenReturn(true)

        val status = ErrorStatus(code = 421, message = "not in swarm", body = """{"snodes":[...]}""".toByteArray().view())
        val error = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = "pub")

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(swarmDirectory).updateSwarmFromResponse(eq("pub"), any())
        verify(swarmDirectory, never()).dropSnodeFromSwarmIfNeeded(any(), any())
    }

    @Test
    fun `421 with pubKey - updateSwarmFromResponse false to drop target snode and Retry`() = runTest {
        val target = snode("target")
        whenever(swarmDirectory.updateSwarmFromResponse(eq("pub"), any())).thenReturn(false)

        val status = ErrorStatus(code = 421, message = "not in swarm", body = """{}""".toByteArray().view())
        val error = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = "pub")

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(swarmDirectory).updateSwarmFromResponse(eq("pub"), any())
        verify(swarmDirectory).dropSnodeFromSwarmIfNeeded(target, "pub")
    }

    @Test
    fun `421 without pubKey to Retry and no update-drop`() = runTest {
        val target = snode("target")

        val status = ErrorStatus(code = 421, message = "not in swarm", body = """{}""".toByteArray().view())
        val error = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = null)

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(swarmDirectory, never()).updateSwarmFromResponse(any(), any())
        verify(swarmDirectory, never()).dropSnodeFromSwarmIfNeeded(any(), any())
    }

    @Test
    fun `502 unparsable data to forceRemove target snode and Retry`() = runTest {
        val target = snode("target")

        val status = ErrorStatus(
            code = 502,
            message = "bad",
            body = "oxend returned unparsable data".toByteArray().view()
        )
        val error = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = "pub")

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(pathManager).handleBadSnode(snode = target, forceRemove = true)
    }

    @Test
    fun `503 destination snode not ready to normal strike and Retry`() = runTest {
        val target = snode("target")

        val status = ErrorStatus(
            code = 503,
            message = "busy",
            body = "Snode not ready".toByteArray().view()
        )
        val error = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = "pub")

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        // NOTE: forceRemove defaults false here
        verify(pathManager).handleBadSnode(snode = target)
        verify(pathManager, never()).handleBadSnode(snode = target, forceRemove = true)
    }

    @Test
    fun `default - non matching DestinationError to Fail`() = runTest {
        val target = snode("target")

        val status = ErrorStatus(code = 418, message = "teapot", body = null)
        val error = OnionError.DestinationError(destination = snodeDest(target), status = status)
        val ctx = SnodeClientFailureContext(targetSnode = target, swarmPublicKey = "pub")

        val decision = manager.onFailure(error, ctx)

        assertThat(decision).isInstanceOf(FailureDecision.Fail::class.java)
        verifyNoInteractions(pathManager)
    }
}

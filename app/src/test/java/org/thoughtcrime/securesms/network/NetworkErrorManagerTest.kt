package org.thoughtcrime.securesms.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.session.libsession.network.NetworkErrorManager
import org.session.libsession.network.NetworkFailureContext
import org.session.libsession.network.model.ErrorStatus
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.util.MockLoggingRule
import org.thoughtcrime.securesms.util.NetworkConnectivity

class NetworkErrorManagerTest {

    @get:Rule
    val logRule = MockLoggingRule()

    private val pathManager = mock<PathManager>()
    private val snodeDirectory = mock<SnodeDirectory>()
    private val connectivity = mock<NetworkConnectivity> {
        on { networkAvailable } doReturn MutableStateFlow(true)
    }

    private val manager = NetworkErrorManager(
        pathManager = pathManager,
        snodeDirectory = snodeDirectory,
        connectivity = connectivity
    )

    private fun snode(id: String) =
        Snode(
            address = "https://$id.example",
            port = 443,
            publicKeySet = Snode.KeySet("ed_$id", "x_$id"),
        )

    @Test
    fun `GuardUnreachable with network penalises guard and retries`() = runTest {
        val guard = snode("guard")
        val path = listOf(guard, snode("m1"), snode("m2"))

        val error = OnionError.GuardUnreachable(
            guard = guard,
            destination = OnionDestination.ServerDestination("h", "v4", "x", "https", 443),
            cause = RuntimeException()
        )

        val decision = manager.onFailure(
            error,
            NetworkFailureContext(path = path, destination = error.destination!!)
        )

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(pathManager).handleBadSnode(guard)
    }

    @Test
    fun `IntermediateNodeUnreachable in path retries`() = runTest {
        val bad = snode("bad")
        val path = listOf(snode("g"), bad, snode("m"))

        whenever(snodeDirectory.getSnodeByKey(bad.publicKeySet!!.ed25519Key))
            .thenReturn(bad)

        val error = OnionError.IntermediateNodeUnreachable(
            reportingNode = path.first(),
            failedPublicKey = bad.publicKeySet.ed25519Key,
            status = ErrorStatus(
                code = 502,
                message = "Next node not found: ${bad.publicKeySet.ed25519Key}",
                body = null
            ),
            destination = OnionDestination.ServerDestination("h", "v4", "x", "https", 443)
        )

        val decision = manager.onFailure(
            error,
            NetworkFailureContext(path = path, destination = error.destination!!)
        )

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(pathManager).handleBadSnode(bad, forceRemove = true)
    }

    @Test
    fun `PathTimedOut penalises path and retries`() = runTest {
        val path = listOf(snode("g"), snode("m1"), snode("m2"))

        val error = OnionError.PathTimedOut(
            status = ErrorStatus(
                code = 504,
                message = "Request time out",
                body = null
            ),
            destination = OnionDestination.ServerDestination("h", "v4", "x", "https", 443)
        )

        val decision = manager.onFailure(
            error,
            NetworkFailureContext(path = path, destination = error.destination!!)
        )

        assertThat(decision).isEqualTo(FailureDecision.Retry)
        verify(pathManager).handleBadPath(path)
    }
}

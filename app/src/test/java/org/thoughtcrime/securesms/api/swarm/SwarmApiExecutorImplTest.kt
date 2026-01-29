package org.thoughtcrime.securesms.api.swarm

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.error.UnknownStatusCodeException
import org.thoughtcrime.securesms.api.snode.SnodeApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.api.snode.SnodeApiResponse
import org.thoughtcrime.securesms.util.MockLoggingRule
import org.thoughtcrime.securesms.util.findCause
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SwarmApiExecutorImplTest {

    @get:Rule
    val loggingRule = MockLoggingRule()

    private lateinit var swarmDirectory: SwarmDirectory
    private lateinit var snodeApiExecutor: SnodeApiExecutor

    private lateinit var swarmSnodeSelector: SwarmSnodeSelector

    private lateinit var executor: SwarmApiExecutorImpl

    @Before
    fun setUp() {
        swarmDirectory = mockk(relaxed = true)
        snodeApiExecutor = mockk(relaxed = true)
        swarmSnodeSelector = mockk(relaxed = true)

        executor = SwarmApiExecutorImpl(
            snodeApiExecutor = snodeApiExecutor,
            swarmDirectory = swarmDirectory,
            swarmSnodeSelector = swarmSnodeSelector
        )
    }

    private val testSnodes = List(10) {
        Snode(
            url = "https://snode$it.example".toHttpUrl(),
            publicKeySet = Snode.KeySet("k1$it", "k2$it")
        )
    }

    @Test
    fun `should perform a successful request`() = runTest {
        val expectResponse = "Success"

        coEvery { swarmSnodeSelector.selectSnode("test") } returns testSnodes[0]
        coEvery { snodeApiExecutor.send(any(), any()) } returns expectResponse

        val snodeApi: SnodeApi<String> = mockk(relaxed = true)

        val actualResponse = executor.execute(SwarmApiRequest(swarmPubKeyHex = "test", api = snodeApi))

        // Validate that the response is as expected
        assertEquals(expectResponse, actualResponse)

        // Validate that the underlying snodeApiExecutor was called with correct parameters
        coVerify {
            snodeApiExecutor.send(any(), eq(SnodeApiRequest(testSnodes[0], snodeApi)))
        }
    }

    @Test
    fun `421 should remove snode from swarm and retry with different snode`() = runTest {
        val callCount = AtomicInteger(0)

        coEvery { swarmSnodeSelector.selectSnode("test") } answers {
            testSnodes[callCount.getAndIncrement() % testSnodes.size]
        }
        coEvery { swarmDirectory.updateSwarmFromResponse(any(), any()) } returns false

        coEvery { snodeApiExecutor.send(any(), any()) } throws UnknownStatusCodeException(code = 421, origin = "snode")

        val context = ApiExecutorContext()

        val api = mockk<SnodeApi<SnodeApiResponse>>()

        val result = runCatching { executor.execute(SwarmApiRequest(swarmPubKeyHex = "test", api = api), ctx = context) }

        // Validated that we have have a retry decision
        val failureDecision = assertNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>()?.failureDecision)
        assertEquals(FailureDecision.Retry, failureDecision)

        // Validated that the snode was removed from the swarm
        verify {
            swarmDirectory.updateSwarmFromResponse(eq("test"), any())
        }
        verify {
            swarmDirectory.dropSnodeFromSwarmIfNeeded(testSnodes[0], "test")
        }

        // Retry the request
        runCatching { executor.execute(SwarmApiRequest(swarmPubKeyHex = "test", api = api), ctx = context) }

        // The second retry should use a different snode now
        coVerify {
            snodeApiExecutor.send(any(), eq(SnodeApiRequest(testSnodes[1], api)))
        }
    }
}
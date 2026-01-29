package org.thoughtcrime.securesms.api.onion

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.onion.OnionBuilder
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.utilities.AESGCM
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiRequest
import org.thoughtcrime.securesms.api.SessionApiResponse
import org.thoughtcrime.securesms.api.direct.DirectSessionApiExecutor
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.execute
import org.thoughtcrime.securesms.api.http.HttpApiExecutor
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.snode.SnodeJsonRequest
import org.thoughtcrime.securesms.util.MockLoggingRule
import org.thoughtcrime.securesms.util.NetworkConnectivity
import org.thoughtcrime.securesms.util.findCause
import java.io.IOException
import kotlin.test.assertIs

class OnionSessionApiExecutorTest {

    @get:Rule
    val logRule = MockLoggingRule()

    lateinit var httpExecutor: HttpApiExecutor
    lateinit var pathManager: PathManager
    lateinit var directSessionApiExecutor: DirectSessionApiExecutor
    lateinit var snodeDirectory: SnodeDirectory
    lateinit var connectivity: NetworkConnectivity


    private lateinit var executor: OnionSessionApiExecutor

    private val path1 = listOf(
        snode("guard1"),
        snode("middle1"),
        snode("exit1"),
    )

    @Before
    fun setUp() {
        pathManager = mockk(relaxed = true)
        httpExecutor = mockk()
        directSessionApiExecutor = mockk()
        snodeDirectory = mockk()
        connectivity = mockk()

        executor = OnionSessionApiExecutor(
            httpApiExecutor = httpExecutor,
            pathManager = pathManager,
            json = Json.Default,
            onionSessionApiErrorManager = OnionSessionApiErrorManager(
                pathManager = pathManager,
                connectivity = connectivity,
            ),
            onionBuilder = mockk {
                every {
                    build(any(), any(), any(), any())
                } answers {
                    OnionBuilder.BuiltOnion(
                        guard = snode("guard"),
                        ciphertext = ByteArray(0),
                        ephemeralPublicKey = ByteArray(0),
                        destinationSymmetricKey = ByteArray(0),
                    )
                }
            },
            onionRequestEncryption = mockk {
                every {
                    encryptPayloadForDestination(any(), any(), any())
                } returns AESGCM.EncryptionResult(
                    ciphertext = ByteArray(0),
                    ephemeralPublicKey = ByteArray(0),
                    symmetricKey = ByteArray(0),
                )

                every {
                    encode(any(), any())
                } returns ByteArray(0)
            }
        )
    }

    private fun snode(id: String) =
        Snode(
            address = "https://$id.example",
            port = 443,
            publicKeySet = Snode.KeySet(ed25519Key = "ed_$id", x25519Key = "x_$id"),
        )

    private suspend fun runExecutor(
        target: Snode = snode("target"),
        ctx: ApiExecutorContext = ApiExecutorContext()
    ): Result<SessionApiResponse.JsonRPCResponse> {
        return runCatching {
            executor.execute(
                SessionApiRequest.SnodeJsonRPC(
                    snode = target,
                    SnodeJsonRequest("test", JsonObject(emptyMap()))
                ),
                ctx = ctx
            )
        }
    }

    @Test
    fun `IOException on guard node while having network should strike guard node and retry`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1
        coEvery {
            httpExecutor.send(any(), any())
        } throws IOException("Failed to connect")
        every { connectivity.networkAvailable } returns MutableStateFlow(true)

        val result = runExecutor()

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Retry)

        assertIs<OnionError.GuardUnreachable>(exception.cause)

        // Should strike the guard node
        coVerify(exactly = 1) { pathManager.handleBadSnode(path1[0], forceRemove = false) }

        // Should not punish the whole path
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `IOException on guard node while having no network should just fail`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1
        coEvery {
            httpExecutor.send(any(), any())
        } throws IOException("Failed to connect")
        every { connectivity.networkAvailable } returns MutableStateFlow(false)

        val result = runExecutor()

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Fail)

        assertIs<OnionError.GuardUnreachable>(exception.cause)

        // Should not strike any node
        coVerify(exactly = 0) { pathManager.handleBadSnode(any(), any()) }

        // Should not punish the whole path
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `Invalid onion response from destination should just fail`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1
        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 200,
            body = HttpBody.Text("OK"),
            headers = emptyMap(),
        )

        val result = runExecutor()
        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Fail)
        assertIs<OnionError.InvalidResponse>(exception.cause)

        // Should not strike any node
        coVerify(exactly = 0) { pathManager.handleBadSnode(any(), any()) }

        // Should not punish any path
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `Unknown error response should just fail`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1
        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 500,
            body = HttpBody.Text("Internal server error"),
            headers = emptyMap(),
        )

        val result = runExecutor()

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Fail)

        assertIs<OnionError.PathError>(exception.cause)

        // Should not strike any node
        coVerify(exactly = 0) { pathManager.handleBadSnode(any(), any()) }

        // Should not punish any path
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `502 next node not found as destination should strike snode and retry`() = runTest {
        val dest = snode("target")
        coEvery { pathManager.getPath(any()) } returns path1
        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 502,
            body = HttpBody.Text("Next node not found: ${dest.ed25519Key}"),
            headers = emptyMap(),
        )

        val result = runExecutor(dest)

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Retry)

        assertIs<OnionError.DestinationUnreachable>(exception.cause)

        // Should strike snode
        coVerify(exactly = 1) { pathManager.handleBadSnode(dest, forceRemove = true) }

        // Should not punish the whole path
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `502 next node not found should strike snode and retry`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1
        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 502,
            body = HttpBody.Text("Next node not found: ${path1[1].ed25519Key}"),
            headers = emptyMap(),
        )

        val result = runExecutor()

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Retry)

        assertIs<OnionError.IntermediateNodeUnreachable>(exception.cause)

        // Should strike snode
        coVerify(exactly = 1) { pathManager.handleBadSnode(path1[1], forceRemove = true) }

        // Should not punish the whole path
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `503 snode not ready should strike snode and retry`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1

        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 503,
            body = HttpBody.Text("Snode not ready: ${path1[1].ed25519Key}"),
            headers = emptyMap(),
        )

        val result = runExecutor()

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Retry)

        assertIs<OnionError.SnodeNotReady>(exception.cause)

        // Should strike the snode that timed out
        coVerify(exactly = 1) { pathManager.handleBadSnode(snode = path1[1], forceRemove = false) }

        // Should not punish the whole path for a single snode timeout
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `503 service node not ready should strike guard node and retry`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1

        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 503,
            body = HttpBody.Text("Service node is not ready:"),
            headers = emptyMap(),
        )

        val result = runExecutor()

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Retry)

        assertIs<OnionError.SnodeNotReady>(exception.cause)

        // Should strike the snode that timed out
        coVerify(exactly = 1) { pathManager.handleBadSnode(snode = path1[0], forceRemove = false) }

        // Should not punish the whole path for a single snode timeout
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `503 server busy should strike guard node and retry`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1

        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 503,
            body = HttpBody.Text("Server busy, try again later"),
            headers = emptyMap(),
        )

        val result = runExecutor()

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Retry)

        assertIs<OnionError.SnodeNotReady>(exception.cause)

        // Should strike the snode that timed out
        coVerify(exactly = 1) { pathManager.handleBadSnode(snode = path1[0], forceRemove = false) }

        // Should not punish the whole path for a single snode timeout
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `504 request timeout should strike path and retry`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1
        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 504,
            body = HttpBody.Text("Request time out"),
            headers = emptyMap(),
        )

        val result = runExecutor()

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Retry)

        assertIs<OnionError.PathTimedOut>(exception.cause)

        // Should not strike any specific snode
        coVerify(exactly = 0) { pathManager.handleBadSnode(any(), any()) }

        // Should punish the whole path for a timeout
        coVerify(exactly = 1) { pathManager.handleBadPath(path1) }
    }

    @Test
    fun `504 request timeout with path override should not strike path and fail`() = runTest {
        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 504,
            body = HttpBody.Text("Request time out"),
            headers = emptyMap(),
        )

        val result = runExecutor(ctx = ApiExecutorContext().also { it.set(OnionSessionApiExecutor.OnionPathOverridesKey, path1) })

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Fail)

        assertIs<OnionError.PathTimedOut>(exception.cause)

        // Should not strike any specific snode
        coVerify(exactly = 0) { pathManager.handleBadSnode(any(), any()) }

        // Should not punish the whole path for a timeout
        coVerify(exactly = 0) { pathManager.handleBadPath(any()) }
    }

    @Test
    fun `500 invalid response from snode should strike the path and retry`() = runTest {
        coEvery { pathManager.getPath(any()) } returns path1
        coEvery {
            httpExecutor.send(any(), any())
        } returns HttpResponse(
            statusCode = 500,
            body = HttpBody.Text("Invalid response from snode"),
            headers = emptyMap(),
        )

        val result = runExecutor()

        val exception =
            checkNotNull(result.exceptionOrNull()?.findCause<ErrorWithFailureDecision>())

        assertThat(exception.failureDecision).isEqualTo(FailureDecision.Retry)

        assertIs<OnionError.InvalidHopResponse>(exception.cause)

        // Should not strike any specific snode
        coVerify(exactly = 0) { pathManager.handleBadSnode(any(), any()) }

        // Should punish the whole path for invalid hop response
        coVerify(exactly = 1) { pathManager.handleBadPath(path1) }
    }
}
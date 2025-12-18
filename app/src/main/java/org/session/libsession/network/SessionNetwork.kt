package org.session.libsession.network

import okhttp3.Request
import kotlinx.coroutines.delay
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.OnionErrorManager
import org.session.libsession.network.onion.OnionFailureContext
import org.session.libsession.network.onion.FailureDecision
import org.session.libsession.network.onion.OnionTransport
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.onion.Version
import org.session.libsession.network.utilities.getBodyForOnionRequest
import org.session.libsession.network.utilities.getHeadersForOnionRequest
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * High-level onion request manager.
 *
 * Responsibilities:
 * - Prepare payloads
 * - Choose onion paths
 * - Retry loop + (light) retry timing/backoff
 * - Delegate all “what do we do with this OnionError?” decisions to OnionErrorManager
 *
 * Not responsible for:
 * - Onion crypto construction or transport I/O (OnionTransport)
 * - Policy / healing logic (OnionErrorManager)
 */
@Singleton
class SessionNetwork @Inject constructor(
    private val pathManager: PathManager,
    private val transport: OnionTransport,
    private val errorManager: OnionErrorManager,
    private val maxAttempts: Int = 2,
    private val baseRetryDelayMs: Long = 250L,
    private val maxRetryDelayMs: Long = 2_000L
) {

    //todo ONION we now have a few places in the app calling SesisonNetwork directly to use
    // sendToSnode or sendToServer. Should this be abstracted away in sessionClient instead?
    // Is there a better way to discern the two?

    /**
     * Send an onion request to a *service node*.
     */
    suspend fun sendToSnode(
        method: Snode.Method,
        parameters: Map<*, *>,
        snode: Snode,
        publicKey: String? = null,
        version: Version = Version.V4
    ): OnionResponse {
        val payload = JsonUtil.toJson(
            mapOf(
                "method" to method.rawValue,
                "params" to parameters
            )
        ).toByteArray()

        val destination = OnionDestination.SnodeDestination(snode)

        // Exclude the destination snode itself from being in the path (old behaviour)
        return sendWithRetry(
            destination = destination,
            payload = payload,
            version = version,
            snodeToExclude = snode,
            targetSnode = snode,
            publicKey = publicKey
        )
    }

    /**
     * Send an onion request to an HTTP server via the snode network.
     */
    suspend fun sendToServer(
        request: Request,
        serverBaseUrl: String,
        x25519PublicKey: String,
        version: Version = Version.V4
    ): OnionResponse {
        val url = request.url
        val payload = generatePayload(request, serverBaseUrl, version)

        val destination = OnionDestination.ServerDestination(
            host = url.host,
            target = version.value,
            x25519PublicKey = x25519PublicKey,
            scheme = url.scheme,
            port = url.port
        )

        return sendWithRetry(
            destination = destination,
            payload = payload,
            version = version,
            snodeToExclude = null,
            targetSnode = null,
            publicKey = null
        )
    }

    private suspend fun sendWithRetry(
        destination: OnionDestination,
        payload: ByteArray,
        version: Version,
        snodeToExclude: Snode?,
        targetSnode: Snode?,
        publicKey: String?
    ): OnionResponse {
        var lastError: Throwable? = null

        for (attempt in 1..maxAttempts) {
            val path: Path = pathManager.getPath(exclude = snodeToExclude)

            try {
                val result = transport.send(
                    path = path,
                    destination = destination,
                    payload = payload,
                    version = version
                )

                return result
            } catch (e: Throwable) {
                val onionError = e as? OnionError ?: OnionError.Unknown(e)

                Log.w("Onion", "Onion error on attempt $attempt/$maxAttempts: $onionError")

                lastError = onionError

                // Delegate all handling + retry decision
                val decision = errorManager.onFailure(
                    error = onionError,
                    ctx = OnionFailureContext(
                        path = path,
                        destination = destination,
                        targetSnode = targetSnode,
                        publicKey = publicKey
                    )
                )

                when (decision) {
                    is FailureDecision.Fail -> throw decision.throwable
                    FailureDecision.Retry -> {
                        if (attempt >= maxAttempts) break
                        delay(computeBackoffDelayMs(attempt))
                        continue
                    }
                }
            }
        }

        throw lastError ?: IllegalStateException("Unknown onion error")
    }

    private fun computeBackoffDelayMs(attempt: Int): Long {
        // Exponential-ish: base * 2^(attempt-1), with jitter, capped
        val exp = baseRetryDelayMs * (1L shl (attempt - 1).coerceAtMost(5))
        val capped = exp.coerceAtMost(maxRetryDelayMs)
        val jitter = Random.nextLong(0, capped / 3 + 1)
        return capped + jitter
    }

    private fun generatePayload(request: Request, server: String, version: Version): ByteArray {
        val headers = request.getHeadersForOnionRequest().toMutableMap()
        val url = request.url
        val urlAsString = url.toString()
        val body = request.getBodyForOnionRequest() ?: "null"

        val endpoint = if (server.length < urlAsString.length) {
            urlAsString.substringAfter(server)
        } else {
            ""
        }

        return if (version == Version.V4) {
            if (request.body != null &&
                headers.keys.none { it.equals("Content-Type", ignoreCase = true) }
            ) {
                headers["Content-Type"] = "application/json"
            }

            val requestPayload = mapOf(
                "endpoint" to endpoint,
                "method" to request.method,
                "headers" to headers
            )

            val requestData = JsonUtil.toJson(requestPayload).toByteArray()
            val prefixData = "l${requestData.size}:".toByteArray(Charsets.US_ASCII)
            val suffixData = "e".toByteArray(Charsets.US_ASCII)

            if (request.body != null) {
                val bodyData = if (body is ByteArray) body else body.toString().toByteArray()
                val bodyLengthData = "${bodyData.size}:".toByteArray(Charsets.US_ASCII)
                prefixData + requestData + bodyLengthData + bodyData + suffixData
            } else {
                prefixData + requestData + suffixData
            }
        } else {
            val payload = mapOf(
                "body" to body,
                "endpoint" to endpoint.removePrefix("/"),
                "method" to request.method,
                "headers" to headers
            )
            JsonUtil.toJson(payload).toByteArray()
        }
    }
}

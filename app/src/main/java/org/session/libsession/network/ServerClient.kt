package org.session.libsession.network

import kotlinx.coroutines.delay
import okhttp3.Request
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.onion.Version
import org.session.libsession.network.utilities.getBodyForOnionRequest
import org.session.libsession.network.utilities.getHeadersForOnionRequest
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Responsible for encoding HTTP requests into the onion format (v3/v4)
 * and sending them via the network.
 */
@Singleton
class ServerClient @Inject constructor(
    private val sessionNetwork: SessionNetwork,
    val errorManager: ServerClientErrorManager
) {
    private val maxAttempts: Int = 2
    private val baseRetryDelayMs: Long = 250L
    private val maxRetryDelayMs: Long = 2_000L

    suspend fun send(
        request: Request,
        serverBaseUrl: String,
        x25519PublicKey: String,
        version: Version = Version.V4
    ): OnionResponse {
        //todo ONION rework Request o be recomputed on retries, for example to help with new timestamps
        val url = request.url

        val destination = OnionDestination.ServerDestination(
            host = url.host,
            target = version.value,
            x25519PublicKey = x25519PublicKey,
            scheme = url.scheme,
            port = url.port
        )

        // the client has its own retry logic, independent from the SessionNetwork's retry logic
        var lastError: OnionError? = null
        for (attempt in 1..maxAttempts) {

            try {
                val payload = generatePayload(request, serverBaseUrl, version)

                return sessionNetwork.sendWithRetry(
                    destination = destination,
                    payload = payload,
                    version = version,
                    snodeToExclude = null,
                    targetSnode = null,
                    publicKey = null
                )
            } catch (e: Throwable) {
                val onionError = e as? OnionError ?: OnionError.Unknown(e)

                Log.w("Onion Request", "Onion error on attempt $attempt/$maxAttempts: $onionError")

                // Delegate all handling + retry decision
                val decision = errorManager.onFailure(
                    error = onionError,
                    ctx = ServerClientFailureContext(
                        url = url,
                        previousError = lastError
                    )
                )

                lastError = onionError

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

        throw lastError ?: IllegalStateException("Unknown Server client error")
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
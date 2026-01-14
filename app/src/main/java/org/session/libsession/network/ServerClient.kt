package org.session.libsession.network

import okhttp3.Request
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.onion.Version
import org.session.libsession.network.utilities.getBodyForOnionRequest
import org.session.libsession.network.utilities.getHeadersForOnionRequest
import org.session.libsession.network.utilities.retryWithBackOff
import org.session.libsignal.utilities.JsonUtil
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Responsible for encoding HTTP requests into the onion format (v3/v4)
 * and sending them via the network.
 */
@Singleton
class ServerClient @Inject constructor(
    private val sessionNetwork: SessionNetwork,
    val errorManager: ServerClientErrorManager
) {

    suspend fun <T> sendWithData(
        requestFactory: suspend () -> Pair<T, Request>,
        serverBaseUrl: String,
        x25519PublicKey: String,
        version: Version = Version.V4,
        operationName: String = "ServerClient.send",
    ): Pair<T, OnionResponse> {
        return retryWithBackOff(
            operationName = operationName,
            classifier = { error, previous ->
                errorManager.onFailure(
                    error = error,
                    ctx = ServerClientFailureContext(
                        url = serverBaseUrl,
                        previousError = previous
                    )
                )
            }
        ) { _ ->
            val (data, request) = requestFactory()
            val url = request.url

            val destination = OnionDestination.ServerDestination(
                host = url.host,
                target = version.value,
                x25519PublicKey = x25519PublicKey,
                scheme = url.scheme,
                port = url.port
            )

            val payload = generatePayload(request, serverBaseUrl, version)

            data to sessionNetwork.sendWithRetry(
                destination = destination,
                payload = payload,
                version = version,
                snodeToExclude = null,
                targetSnode = null,
                publicKey = null
            )
        }
    }

    /**
     * The request is sent as a lambda in order to be recalculated as part of the retry strategy.
     * This is useful for things like timestamps that  might have been updated
     * as part of a clock resync
     */
    suspend fun send(
        requestFactory: suspend () -> Request,
        serverBaseUrl: String,
        x25519PublicKey: String,
        version: Version = Version.V4,
        operationName: String = "ServerClient.send",
    ): OnionResponse {
        return sendWithData(
            requestFactory = {
                val request = requestFactory()
                Unit to request
            },
            serverBaseUrl = serverBaseUrl,
            x25519PublicKey = x25519PublicKey,
            version = version,
            operationName = operationName
        ).second
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
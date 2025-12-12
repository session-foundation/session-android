package org.session.libsession.network

import okhttp3.Request
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.OnionTransport
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.onion.Version
import org.session.libsession.network.utilities.getBodyForOnionRequest
import org.session.libsession.network.utilities.getHeadersForOnionRequest
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode

/**
 * High-level onion request manager.
 * It prepares payloads, chooses onion paths, analyzes failures, repairs the path graph,
 * implements retry rules, and returns final user-level responses.
 * It does not build onion encryption or send anything over the network, that part
 * is left to an implementation of an OnionTransport
 */
class SessionNetwork(
    private val pathManager: PathManager,
    private val transport: OnionTransport,
    private val maxAttempts: Int = 3
) {

    /**
     * Send an onion request to a *service node* (RPC).
     */
    suspend fun sendToSnode(
        method: Snode.Method,
        parameters: Map<*, *>,
        snode: Snode,
        version: Version = Version.V4
    ): Result<OnionResponse> {
        val payload = JsonUtil.toJson(
            mapOf(
                "method" to method.rawValue,
                "params" to parameters
            )
        ).toByteArray()

        val destination = OnionDestination.SnodeDestination(snode)

        // Exclude the snode itself from being in the path (matches old behaviour)
        return sendWithRetry(
            destination = destination,
            payload = payload,
            version = version,
            snodeToExclude = snode
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
    ): Result<OnionResponse> {
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
            snodeToExclude = null
        )
    }

    private suspend fun sendWithRetry(
        destination: OnionDestination,
        payload: ByteArray,
        version: Version,
        snodeToExclude: Snode?
    ): Result<OnionResponse> {
        var lastError: Throwable? = null

        repeat(maxAttempts) { attempt ->
            val path = pathManager.getPath(exclude = snodeToExclude)

            val result = transport.send(
                path = path,
                destination = destination,
                payload = payload,
                version = version
            )

            if (result.isSuccess) return result

            val error = result.exceptionOrNull()
            if (error !is OnionError) {
                // Transport returned some unexpected Throwable
                return Result.failure(error ?: IllegalStateException("Unknown transport error"))
            }

            Log.w("Onion", "Onion error on attempt ${attempt + 1}/$maxAttempts: $error")

            handleError(path, error)

            if (!mustRetry(error, attempt)) {
                return Result.failure(error)
            }

            lastError = error
        }

        return Result.failure(lastError ?: IllegalStateException("Unknown onion error"))
    }

    /**
     * Decide whether to retry based on the error type and current attempt.
     */
    private fun mustRetry(error: OnionError, attempt: Int): Boolean {
        if (attempt + 1 >= maxAttempts) return false

        return when (error) {
            is OnionError.DestinationError,
            is OnionError.ClockOutOfSync -> {
                false
            }
            is OnionError.GuardConnectionFailed,
            is OnionError.PathError,
            is OnionError.PathErrorNonPenalizing,
            is OnionError.IntermediateNodeFailed,
            is OnionError.InvalidResponse,
            is OnionError.Unknown -> {
                true
            }
        }
    }

    /**
     * Map an OnionError into path-level healing operations.
     */
    private fun handleError(path: Path, error: OnionError) {
        when (error) {
            is OnionError.GuardConnectionFailed,
            is OnionError.PathError,
            is OnionError.InvalidResponse,
            is OnionError.Unknown -> {
                // We don't know which hop is bad; drop the whole path.
                Log.w("Onion", "Dropping entire path due to error: $error")
                pathManager.handleBadPath(path)
            }

            is OnionError.IntermediateNodeFailed -> {
                val failedKey = error.failedPublicKey
                if (failedKey == null) {
                    Log.w("Onion", "Intermediate node failed but no key given; dropping path")
                    pathManager.handleBadPath(path)
                } else {
                    val bad = path.firstOrNull { it.publicKeySet?.ed25519Key == failedKey }
                    if (bad != null) {
                        Log.w("Onion", "Dropping bad snode $bad in path")
                        pathManager.handleBadSnode(bad)
                    } else {
                        Log.w("Onion", "Failed node key not in path; dropping path")
                        pathManager.handleBadPath(path)
                    }
                }
            }

            is OnionError.PathErrorNonPenalizing,
            is OnionError.DestinationError,
            is OnionError.ClockOutOfSync -> {
                // Path is considered healthy; do not mutate paths.
                Log.d("Onion", "Non penalizing error: $error")
            }
        }
    }

    /**
     * Equivalent to the old generatePayload() from OnionRequestAPI.
     */
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

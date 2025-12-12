package org.session.libsession.network.onion.http

import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.onion.OnionBuilder
import org.session.libsession.network.onion.OnionRequestEncryption
import org.session.libsession.network.onion.OnionTransport
import org.session.libsession.network.onion.Version
import org.session.libsession.utilities.AESGCM
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Snode
import org.session.libsignal.utilities.toHexString

private val NON_PENALIZING_STATUSES = setOf(403, 404, 406, 425)
private const val REQUIRE_BLINDING_MESSAGE =
    "Invalid authentication: this server requires the use of blinded ids"

/**
 * Builds onion layers, sends them over HTTP to the guard,
 * receives and decrypts the onion response,
 * and maps low-level protocol/transport errors into onion errors.
 * It does not choose paths, retry, or apply healing logic.
 */
class HttpOnionTransport : OnionTransport {

    override suspend fun send(
        path: List<Snode>,
        destination: OnionDestination,
        payload: ByteArray,
        version: Version
    ): Result<OnionResponse> {
        require(path.isNotEmpty()) { "Path must not be empty" }

        val guard = path.first()

        val built = try {
            OnionBuilder.build(path, destination, payload, version)
        } catch (t: Throwable) {
            return Result.failure(OnionError.Unknown(t))
        }

        val url = "${guard.address}:${guard.port}/onion_req/v2"

        val params = mapOf(
            "ephemeral_key" to built.ephemeralPublicKey.toHexString()
        )

        val body = try {
            OnionRequestEncryption.encode(
                ciphertext = built.ciphertext,
                json = params
            )
        } catch (t: Throwable) {
            return Result.failure(OnionError.Unknown(t))
        }

        val responseBytes: ByteArray = try {
            HTTP.execute(HTTP.Verb.POST, url, body)
        } catch (httpEx: HTTP.HTTPRequestFailedException) {
            // HTTP error from guard (we never got an onion-level response)
            return Result.failure(mapGuardHttpError(guard, httpEx))
        } catch (t: Throwable) {
            // TCP / DNS / TLS / timeout etc. reaching guard
            return Result.failure(OnionError.GuardConnectionFailed(guard, t))
        }

        // We have an onion-level response from the guard; decrypt & interpret
        return handleResponse(
            rawResponse = responseBytes,
            destinationSymmetricKey = built.destinationSymmetricKey,
            destination = destination,
            version = version
        )
    }

    /**
     * Map HTTP errors from the guard (before onion decryption)
     */
    private fun mapGuardHttpError(
        guard: Snode,
        ex: HTTP.HTTPRequestFailedException
    ): OnionError {
        val json = ex.json
        val message = json?.get("result") as? String
        val statusCode = ex.statusCode

        // Special onion path error: "Next node not found: <ed25519>"
        val prefix = "Next node not found: "
        if (message != null && message.startsWith(prefix)) {
            val failedPk = message.removePrefix(prefix)
            return OnionError.IntermediateNodeFailed(
                reportingNode = guard,
                failedPublicKey = failedPk
            )
        }

        // Non-penalising codes: treat as destination-level error (path OK)
        if (statusCode in NON_PENALIZING_STATUSES || message == "Loki Server error") {
            return OnionError.DestinationError(
                code = statusCode,
                body = message
            )
        }

        // Otherwise: guard rejected / misbehaved
        return OnionError.GuardProtocolError(
            guard = guard,
            code = statusCode,
            body = message
        )
    }

    /**
     * Handle an onion-encrypted response
     */
    private fun handleResponse(
        rawResponse: ByteArray,
        destinationSymmetricKey: ByteArray,
        destination: OnionDestination,
        version: Version
    ): Result<OnionResponse> {
        return when (version) {
            Version.V4 -> handleV4Response(rawResponse, destinationSymmetricKey, destination)
            Version.V2, Version.V3 -> {
                //todo ONION add support for v2/v3
                Result.failure(
                    OnionError.Unknown(
                        UnsupportedOperationException("Need to implement - TEMP")
                    )
                )
            }
        }
    }

    private fun handleV4Response(
        response: ByteArray,
        destinationSymmetricKey: ByteArray,
        destination: OnionDestination
    ): Result<OnionResponse> {
        try {
            if (response.size <= AESGCM.ivSize) {
                return Result.failure(OnionError.InvalidResponse(response))
            }

            val plaintext = AESGCM.decrypt(response, symmetricKey = destinationSymmetricKey)

            if (plaintext.isEmpty() || plaintext[0] != 'l'.code.toByte()) {
                return Result.failure(OnionError.InvalidResponse(response))
            }

            val infoSepIdx = plaintext.indexOfFirst { it == ':'.code.toByte() }
            if (infoSepIdx <= 1) {
                return Result.failure(OnionError.InvalidResponse(response))
            }

            val infoLenSlice = plaintext.slice(1 until infoSepIdx)
            val infoLength = infoLenSlice
                .toByteArray()
                .toString(Charsets.US_ASCII)
                .toIntOrNull()
                ?: return Result.failure(OnionError.InvalidResponse(response))

            val infoStartIndex = "l$infoLength".length + 1
            val infoEndIndex = infoStartIndex + infoLength
            if (infoEndIndex > plaintext.size) {
                return Result.failure(OnionError.InvalidResponse(response))
            }

            val infoBytes = plaintext.slice(infoStartIndex until infoEndIndex).toByteArray()
            @Suppress("UNCHECKED_CAST")
            val responseInfo = JsonUtil.fromJson(infoBytes, Map::class.java) as Map<*, *>

            val statusCode = responseInfo["code"].toString().toInt()

            // clock out-of-sync special handling
            if (statusCode == 406 || statusCode == 425) {
                val body = "Your clock is out of sync with the service node network."
                return Result.failure(
                    OnionError.ClockOutOfSync(
                        code = statusCode,
                        body = body
                    )
                )
            }

            if (statusCode !in 200..299) {
                // For 400 from server, we might have a body in the second part
                val responseBodySlice =
                    if (destination is OnionDestination.ServerDestination && statusCode == 400) {
                        plaintext.getBody(infoLength, infoEndIndex)
                    } else null

                val bodyStr = responseBodySlice?.decodeToString()
                val bodyOrMsg = bodyStr ?: (responseInfo["message"]?.toString())

                // Special case: require blinding message (still treated as destination error)
                if (bodyStr == REQUIRE_BLINDING_MESSAGE) {
                    return Result.failure(
                        OnionError.DestinationError(
                            code = statusCode,
                            body = bodyStr
                        )
                    )
                }

                return Result.failure(
                    OnionError.DestinationError(
                        code = statusCode,
                        body = bodyOrMsg
                    )
                )
            }

            // 2xx: success. There may or may not be a body.
            val responseBody = plaintext.getBody(infoLength, infoEndIndex)
            return if (responseBody.isEmpty()) {
                Result.success(OnionResponse(info = responseInfo, body = null))
            } else {
                Result.success(OnionResponse(info = responseInfo, body = responseBody))
            }
        } catch (t: Throwable) {
            return Result.failure(OnionError.InvalidResponse(response))
        }
    }

    /**
     * V4 layout helper: extracts the optional body part from `lN:json...e`.
     */
    private fun ByteArray.getBody(infoLength: Int, infoEndIndex: Int): ByteArraySlice {
        val infoLengthStringLength = infoLength.toString().length
        // minimum layout: l<infoLength>:<info>e
        if (size <= infoLength + infoLengthStringLength + 2 /* l and e */) {
            return ByteArraySlice.EMPTY
        }
        // There is extra data: parse the second length / body section.
        val dataSlice = view(infoEndIndex + 1 until size - 1)
        val dataSepIdx = dataSlice.asList().indexOfFirst { it.toInt() == ':'.code }
        if (dataSepIdx == -1) return ByteArraySlice.EMPTY
        return dataSlice.view(dataSepIdx + 1 until dataSlice.len)
    }
}

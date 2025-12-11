package org.session.libsession.network.onion.http

import kotlin.text.Charsets
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.onion.OnionBuilder
import org.session.libsession.network.onion.OnionRequestEncryption
import org.session.libsession.network.onion.OnionTransport
import org.session.libsession.network.onion.Version
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.AESGCM.ivSize
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Snode
import org.session.libsignal.utilities.toHexString

class HttpOnionTransporter : OnionTransport {

    override suspend fun send(
        path: List<Snode>,
        destination: OnionDestination,
        payload: ByteArray,
        version: Version
    ): Result<OnionResponse> {
        return try {
            val built = OnionBuilder.build(path, destination, payload, version)
            val guard = built.guard
            val url = "${guard.address}:${guard.port}/onion_req/v2"

            val params = mapOf(
                "ephemeral_key" to built.ephemeralPublicKey.toHexString()
            )

            val body = OnionRequestEncryption.encode(
                ciphertext = built.ciphertext,
                json = params
            )

            val responseBytes = try {
                HTTP.execute(HTTP.Verb.POST, url, body)
            } catch (httpEx: HTTP.HTTPRequestFailedException) {
                // This is an HTTP-level failure to the guard
                return Result.failure(classifyHttpFailure(path, destination, httpEx))
            } catch (t: Throwable) {
                return Result.failure(
                    OnionError.GuardConnectionFailed(guard, t)
                )
            }

            val response = decodeResponse(responseBytes, destination, version, built.destinationSymmetricKey)
            Result.success(response)
        } catch (e: OnionError) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(OnionError.Unknown(t))
        }
    }

    /**
     * Turn a HTTP.HTTPRequestFailedException from the guard into a structured OnionError.
     *
     * This is where we replicate the old logic that interpreted "Next node not found", etc.
     */
    private fun classifyHttpFailure(
        path: List<Snode>,
        destination: OnionDestination,
        ex: HTTP.HTTPRequestFailedException
    ): OnionError {
        val json = ex.json
        val statusCode = ex.statusCode
        val message = json?.get("result") as? String
        val guard = path.firstOrNull()

        val prefix = "Next node not found: "
        if (message != null && message.startsWith(prefix)) {
            val failedKey = message.removePrefix(prefix)
            return OnionError.IntermediateNodeFailed(
                reportingNode = guard,
                failedPublicKey = failedKey
            )
        }

        // Destination-related 4xx/5xx that we don't want to penalize path for
        if (destination is OnionDestination.ServerDestination &&
            (statusCode in 500..504 || statusCode == 400) &&
            (ex.body?.contains(destination.host) == true)
        ) {
            return OnionError.DestinationError(code = statusCode, body = ex.body)
        }

        // Special clock out of sync codes from your old logic
        if (statusCode == 406 || statusCode == 425) {
            return OnionError.ClockOutOfSync(code = statusCode, body = message)
        }

        // 404, 403, etc. that are likely application or resource errors
        if (statusCode in listOf(400, 401, 403, 404)) {
            return OnionError.DestinationError(code = statusCode, body = message)
        }

        // Fallback: treat as guard protocol error
        return OnionError.GuardProtocolError(
            guard = guard,
            code = statusCode,
            body = message
        )
    }

    private fun decodeResponse(
        response: ByteArray,
        destination: OnionDestination,
        version: Version,
        destinationSymmetricKey: ByteArray
    ): OnionResponse {
        return when (version) {
            Version.V4 -> decodeV4(response, destination, destinationSymmetricKey)
            Version.V2, Version.V3 -> decodeLegacy(response, destination, destinationSymmetricKey)
        }
    }

    private fun decodeV4(
        response: ByteArray,
        destination: OnionDestination,
        destinationSymmetricKey: ByteArray
    ): OnionResponse {
        if (response.size <= ivSize) throw OnionError.InvalidResponse(response)

        val plaintext = try {
            AESGCM.decrypt(response, symmetricKey = destinationSymmetricKey)
        } catch (e: Throwable) {
            throw OnionError.InvalidResponse(response)
        }

        if (!byteArrayOf(plaintext.first()).contentEquals("l".toByteArray())) {
            throw OnionError.InvalidResponse(response)
        }

        val infoSepIdx = plaintext.indexOfFirst { byteArrayOf(it).contentEquals(":".toByteArray()) }
        val infoLenSlice = plaintext.slice(1 until infoSepIdx)
        val infoLength = infoLenSlice.toByteArray().toString(Charsets.US_ASCII).toIntOrNull()
            ?: throw OnionError.InvalidResponse(response)

        if (infoLenSlice.size <= 1) throw OnionError.InvalidResponse(response)

        val infoStartIndex = "l$infoLength".length + 1
        val infoEndIndex = infoStartIndex + infoLength
        val info = plaintext.slice(infoStartIndex until infoEndIndex)
        val responseInfo = JsonUtil.fromJson(info.toByteArray(), Map::class.java)

        val statusCode = responseInfo["code"].toString().toInt()

        when (statusCode) {
            406, 425 -> throw OnionError.ClockOutOfSync(statusCode, responseInfo["result"]?.toString())
            !in 200..299 -> {
                val responseBody =
                    if (destination is OnionDestination.ServerDestination && statusCode == 400) {
                        plaintext.getBody(infoLength, infoEndIndex)
                    } else null

                val requireBlinding =
                    "Invalid authentication: this server requires the use of blinded ids"

                if (responseBody != null && responseBody.decodeToString() == requireBlinding) {
                    // You could introduce a dedicated error subtype if you want.
                    throw OnionError.DestinationError(400, requireBlinding)
                } else {
                    throw OnionError.DestinationError(statusCode, responseBody?.decodeToString())
                }
            }
        }

        val responseBody = plaintext.getBody(infoLength, infoEndIndex)

        return if (responseBody.isEmpty()) {
            OnionResponse(responseInfo, null)
        } else {
            OnionResponse(responseInfo, responseBody)
        }
    }

    private fun decodeLegacy(
        response: ByteArray,
        destination: OnionDestination,
        destinationSymmetricKey: ByteArray
    ): OnionResponse {
        val json = try {
            JsonUtil.fromJson(response, Map::class.java)
        } catch (e: Exception) {
            mapOf("result" to response.decodeToString())
        }

        val base64EncodedIVAndCiphertext =
            json["result"] as? String ?: throw OnionError.InvalidResponse(response)

        val ivAndCiphertext = Base64.decode(base64EncodedIVAndCiphertext)

        val plaintext = try {
            AESGCM.decrypt(ivAndCiphertext, symmetricKey = destinationSymmetricKey)
        } catch (e: Throwable) {
            throw OnionError.InvalidResponse(response)
        }

        val parsed = try {
            JsonUtil.fromJson(plaintext.toString(Charsets.UTF_8), Map::class.java)
        } catch (e: Exception) {
            throw OnionError.InvalidResponse(plaintext)
        }

        val statusCode = parsed["status_code"] as? Int ?: parsed["status"] as Int

        if (statusCode == 406) {
            throw OnionError.ClockOutOfSync(statusCode, parsed["result"]?.toString())
        }

        if (parsed["body"] != null) {
            @Suppress("UNCHECKED_CAST")
            val body = if (parsed["body"] is Map<*, *>) {
                parsed["body"] as Map<*, *>
            } else {
                val bodyAsString = parsed["body"] as String
                JsonUtil.fromJson(bodyAsString, Map::class.java)
            }

            if (statusCode != 200) {
                throw OnionError.DestinationError(statusCode, body.toString())
            }

            return OnionResponse(body, JsonUtil.toJson(body).toByteArray().view())
        } else {
            if (statusCode != 200) {
                throw OnionError.DestinationError(statusCode, parsed.toString())
            }

            return OnionResponse(parsed, JsonUtil.toJson(parsed).toByteArray().view())
        }
    }

    private fun ByteArray.getBody(infoLength: Int, infoEndIndex: Int): ByteArraySlice {
        val infoLengthStringLength = infoLength.toString().length
        if (size <= infoLength + infoLengthStringLength + 2 /* l and e */) {
            return ByteArraySlice.EMPTY
        }
        val dataSlice = view(infoEndIndex + 1 until size - 1)
        val dataSepIdx = dataSlice.asList().indexOfFirst { it.toInt() == ':'.code }
        return dataSlice.view(dataSepIdx + 1 until dataSlice.len)
    }
}

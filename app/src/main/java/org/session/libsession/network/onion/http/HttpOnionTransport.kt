package org.session.libsession.network.onion.http

import org.session.libsession.network.model.ErrorStatus
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
            return Result.failure(mapPathHttpError(guard, httpEx))
        } catch (t: Throwable) {
            // TCP / DNS / TLS / timeout etc. reaching guard
            return Result.failure(OnionError.GuardUnreachable(guard, t))
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
     * Errors thrown by the guard / path hop BEFORE we get an onion-encrypted reply.
     */
    private fun mapPathHttpError(
        node: Snode,
        ex: HTTP.HTTPRequestFailedException
    ): OnionError {
        val json = ex.json
        val message = (json?.get("result") as? String)
            ?: (json?.get("message") as? String)

        val statusCode = ex.statusCode

        // Special onion path error: "Next node not found: <ed25519>"
        val prefix = "Next node not found: "
        if (message != null && message.startsWith(prefix)) {
            val failedPk = message.removePrefix(prefix)
            return OnionError.IntermediateNodeFailed(
                reportingNode = node,
                failedPublicKey = failedPk
            )
        }

        return OnionError.PathError(
            node = node,
            status = ErrorStatus(
                code = statusCode,
                message = message,
                body = null
            )
        )
    }

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
                Result.failure(OnionError.Unknown(UnsupportedOperationException("Need to implement v2/v3")))
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
                return Result.failure(OnionError.InvalidResponse())
            }

            val decrypted = AESGCM.decrypt(response, symmetricKey = destinationSymmetricKey)

            if (decrypted.isEmpty() || decrypted[0] != 'l'.code.toByte()) {
                return Result.failure(OnionError.InvalidResponse())
            }

            val infoSepIdx = decrypted.indexOfFirst { it == ':'.code.toByte() }
            if (infoSepIdx <= 1) return Result.failure(OnionError.InvalidResponse())

            val infoLenSlice = decrypted.slice(1 until infoSepIdx)
            val infoLength = infoLenSlice.toByteArray().toString(Charsets.US_ASCII).toIntOrNull()
                ?: return Result.failure(OnionError.InvalidResponse())

            val infoStartIndex = "l$infoLength".length + 1
            val infoEndIndex = infoStartIndex + infoLength
            if (infoEndIndex > decrypted.size) return Result.failure(OnionError.InvalidResponse())

            val infoBytes = decrypted.slice(infoStartIndex until infoEndIndex).toByteArray()
            @Suppress("UNCHECKED_CAST")
            val responseInfo = JsonUtil.fromJson(infoBytes, Map::class.java) as Map<*, *>

            val statusCode = responseInfo["code"].toString().toInt()

            if (statusCode !in 200..299) {
                // Optional "body" part for some server errors (notably 400)
                val bodySlice =
                    if (destination is OnionDestination.ServerDestination && statusCode == 400) {
                        decrypted.getBody(infoLength, infoEndIndex)
                    } else null

                return Result.failure(
                    OnionError.DestinationError(
                        status = ErrorStatus(
                            code = statusCode,
                            message = responseInfo["message"]?.toString(),
                            body = bodySlice
                        )
                    )
                )
            }

            val responseBody = decrypted.getBody(infoLength, infoEndIndex)
            return if (responseBody.isEmpty()) {
                Result.success(OnionResponse(info = responseInfo, body = null))
            } else {
                Result.success(OnionResponse(info = responseInfo, body = responseBody))
            }
        } catch (t: Throwable) {
            return Result.failure(OnionError.InvalidResponse(t))
        }
    }

    private fun ByteArray.getBody(infoLength: Int, infoEndIndex: Int): ByteArraySlice {
        val infoLengthStringLength = infoLength.toString().length
        if (size <= infoLength + infoLengthStringLength + 2) return ByteArraySlice.EMPTY

        val dataSlice = view(infoEndIndex + 1 until size - 1)
        val dataSepIdx = dataSlice.asList().indexOfFirst { it.toInt() == ':'.code }
        if (dataSepIdx == -1) return ByteArraySlice.EMPTY

        return dataSlice.view(dataSepIdx + 1 until dataSlice.len)
    }
}

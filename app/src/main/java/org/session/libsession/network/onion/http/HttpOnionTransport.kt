package org.session.libsession.network.onion.http

import androidx.annotation.VisibleForTesting
import dagger.Lazy
import org.session.libsession.network.model.ErrorStatus
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.OnionBuilder
import org.session.libsession.network.onion.OnionRequestEncryption
import org.session.libsession.network.onion.OnionTransport
import org.session.libsession.network.onion.Version
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.utilities.AESGCM
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.ForkInfo
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.session.libsignal.utilities.toHexString
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

@Singleton
class HttpOnionTransport @Inject constructor(
    private val snodeDirectory: Lazy<SnodeDirectory>
) : OnionTransport {

    override suspend fun send(
        path: Path,
        destination: OnionDestination,
        payload: ByteArray,
        version: Version
    ): OnionResponse {
        require(path.isNotEmpty()) { "Path must not be empty" }

        val guard = path.first()

        val built = try {
            OnionBuilder.build(path, destination, payload, version)
        } catch (t: Throwable) {
            throw OnionError.EncodingError(destination, t,)
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
            throw OnionError.EncodingError(destination, t)
        }

        val responseBytes: ByteArray = try {
            HTTP.execute(HTTP.Verb.POST, url, body)
        } catch(e: CancellationException){
            throw e
        } catch (httpEx: HTTP.HTTPRequestFailedException) {
            // HTTP error from guard (we never got an onion-level response)
            throw mapPathHttpError(guard, httpEx, path, destination)
        } catch (e: IOException){
            throw OnionError.GuardUnreachable(guard, destination, e)
        } catch (t: Throwable) {
            throw OnionError.Unknown(destination,t)
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
    @VisibleForTesting
    internal fun mapPathHttpError(
        node: Snode,
        ex: HTTP.HTTPRequestFailedException,
        path: Path,
        destination: OnionDestination
    ): OnionError {
        val message = ex.body
        val statusCode = ex.statusCode

        // ---- 502: hop can't find/contact next hop ----
        val nextNodeNotFound = "Next node not found: "
        val nextNodeUnreachable = "Next node is currently unreachable: "

        // to extract the  key from the error message
        fun parseNextHopPk(msg: String): String? = when {
            msg.startsWith(nextNodeNotFound) -> msg.removePrefix(nextNodeNotFound).trim()
            msg.startsWith(nextNodeUnreachable) -> msg.removePrefix(nextNodeUnreachable).trim()
            else -> null
        }

        val failedPk = message?.let(::parseNextHopPk)
        if (statusCode == 502 && failedPk != null) {
            val destPk = (destination as? OnionDestination.SnodeDestination)?.snode?.publicKeySet?.ed25519Key

            return if (destPk != null && failedPk == destPk) {
                OnionError.DestinationUnreachable(
                    status = ErrorStatus(code = statusCode, message = message, body = null),
                    destination = destination
                )
            } else {
                OnionError.IntermediateNodeUnreachable(
                    reportingNode = node,
                    failedPublicKey = failedPk,
                    status = ErrorStatus(code = statusCode, message = message, body = null),
                    destination = destination
                )
            }
        }

        // ---- 503: "Snode not ready" ----
        if (statusCode == 503) {
            val snodeNotReadyPrefix = "Snode not ready: "
            val snodeNotReady = message?.startsWith(snodeNotReadyPrefix) == true

            val guardNotReady =
                message?.startsWith("Service node is not ready:") == true ||
                        message?.startsWith("Server busy, try again later") == true

            if(guardNotReady){
                return OnionError.SnodeNotReady(
                    failedPublicKey = path.first().publicKeySet?.ed25519Key,
                    status = ErrorStatus(code = statusCode, message = message, body = null),
                    destination = destination
                )
            }
            else if (snodeNotReady) {
                val pk = message.removePrefix(snodeNotReadyPrefix).trim()
                return OnionError.SnodeNotReady(
                    failedPublicKey = pk,
                    status = ErrorStatus(code = statusCode, message = message, body = null),
                    destination = destination
                )
            }
        }

        // ---- 504: timeouts along path ----
        if (statusCode == 504 && message?.contains("Request time out", ignoreCase = true) == true) {
            return OnionError.PathTimedOut(
                status = ErrorStatus(code = statusCode, message = message, body = null),
                destination = destination
            )
        }

        // ---- 500: invalid response from next hop ----
        //todo ONION currently we have no handling of 5xx for snode destination as it's unclear how to best handle them
        if (statusCode == 500 && message?.contains("Invalid response from snode", ignoreCase = true) == true) {
            return OnionError.InvalidHopResponse(
                node = node,
                status = ErrorStatus(code = statusCode, message = message, body = null),
                destination = destination
            )
        }

        // Default: generic path error
        return OnionError.PathError(
            node = node,
            status = ErrorStatus(code = statusCode, message = message, body = null),
            destination = destination
        )
    }

    private fun handleResponse(
        rawResponse: ByteArray,
        destinationSymmetricKey: ByteArray,
        destination: OnionDestination,
        version: Version
    ): OnionResponse {
        //Log.i("Onion Request", "Got a successful response from request")
        return when (version) {
            Version.V4 -> handleV4Response(rawResponse, destinationSymmetricKey, destination)
            Version.V3 -> handleV2V3Response(rawResponse, destinationSymmetricKey, destination)
        }
    }

    private fun handleV4Response(
        response: ByteArray,
        destinationSymmetricKey: ByteArray,
        destination: OnionDestination
    ): OnionResponse {
        try {
            if (response.size <= AESGCM.ivSize) {
                throw OnionError.InvalidResponse(destination)
            }

            val decrypted = AESGCM.decrypt(response, symmetricKey = destinationSymmetricKey)

            //todo ONION is it really this class' responsibility to decode the decrypted payload instead of passing it to a higher level
            if (decrypted.isEmpty() || decrypted[0] != 'l'.code.toByte()) {
                throw RuntimeException("Error decoding payload")
            }

            val infoSepIdx = decrypted.indexOfFirst { it == ':'.code.toByte() }
            if (infoSepIdx <= 1) error("Error decoding payload")

            val infoLenSlice = decrypted.slice(1 until infoSepIdx)
            val infoLength = infoLenSlice.toByteArray().toString(Charsets.US_ASCII).toIntOrNull()
                ?: error("Error decoding payload")

            val infoStartIndex = "l$infoLength".length + 1
            val infoEndIndex = infoStartIndex + infoLength
            if (infoEndIndex > decrypted.size) error("Error decoding payload")

            val infoSlice = decrypted.view(infoStartIndex until infoEndIndex)
            val responseInfo = JsonUtil.fromJson(infoSlice, Map::class.java) as Map<*, *>

            val statusCode = responseInfo["code"].toString().toInt()

            if (statusCode !in 200..299) {
                Log.i("Onion Request", "Successful response decrypted, but non-2xx status code: $statusCode")

                error("Non-2xx status code in response")
            }

            val responseBody = decrypted.getBody(infoLength, infoEndIndex)
            return if (responseBody.isEmpty()) {
                OnionResponse(info = responseInfo, body = null)
            } else {
                OnionResponse(info = responseInfo, body = responseBody)
            }
        } catch (e: OnionError) {
            throw e
        } catch (t: Throwable) {
            throw OnionError.InvalidResponse(destination, t)
        }
    }

    private fun handleV2V3Response(
        rawResponse: ByteArray,
        destinationSymmetricKey: ByteArray,
        destination:OnionDestination
    ): OnionResponse {
        // Outer wrapper: {"result": "<base64(iv+ciphertext)>"}
        val jsonWrapper: Map<*, *> = try {
            JsonUtil.fromJson(rawResponse, Map::class.java) as Map<*, *>
        } catch (e: Exception) {
            mapOf("result" to rawResponse.decodeToString())
        }

        val base64Ciphertext = jsonWrapper["result"] as? String
            ?: throw OnionError.InvalidResponse(destination, Exception("V2/V3 response missing 'result'"))

        val ivAndCiphertext: ByteArray = try {
            Base64.decode(base64Ciphertext)
        } catch (e: Exception) {
            throw OnionError.InvalidResponse(destination, Exception("Base64 decode failed", e))
        }

        val plaintextBytes: ByteArray = try {
            AESGCM.decrypt(ivAndCiphertext, symmetricKey = destinationSymmetricKey)
        } catch (e: Exception) {
            throw OnionError.InvalidResponse(destination, Exception("Decryption failed", e))
        }

        val plaintextString = plaintextBytes.toString(Charsets.UTF_8)

        val innerJson: Map<*, *> = try {
            JsonUtil.fromJson(plaintextString, Map::class.java) as Map<*, *>
        } catch (e: Exception) {
            throw RuntimeException("Failed to parse decrypted payload as JSON", e)
        }

        val statusCode: Int = checkNotNull(
            (innerJson["status_code"] as? Number)?.toInt()
                ?: (innerJson["status"] as? Number)?.toInt()) {
            "Response missing status code"
        }

        val bodyObj: Any? = innerJson["body"]

        val normalizedBody: Any? = when (bodyObj) {
            null -> null

            is Map<*, *> -> {
                processForkInfo(bodyObj)
                bodyObj
            }

            is String -> {
                val parsed: Any = try {
                    JsonUtil.fromJson(bodyObj, Map::class.java)
                } catch (e: Exception) {
                    throw RuntimeException("Failed to parse body string as JSON", e)
                }

                val parsedMap = parsed as? Map<*, *>
                    ?: throw RuntimeException("Parsed body was not a JSON object")

                processForkInfo(parsedMap)
                parsedMap
            }

            else -> {
                throw RuntimeException("Unexpected body type: ${bodyObj::class.java}")
            }
        }

        fun extractMessage(from: Map<*, *>): String? =
            (from["result"] as? String) ?: (from["message"] as? String)

        if (statusCode !in 200..299) {
            error("Non-2xx status code in response")
        }

        return if (normalizedBody != null) {
            val bodyMap = normalizedBody as Map<*, *>
            val bodyBytes: ByteArraySlice = JsonUtil.toJson(bodyMap).toByteArray().view()
            OnionResponse(info = bodyMap, body = bodyBytes)
        } else {
            val jsonBytes: ByteArraySlice = JsonUtil.toJson(innerJson).toByteArray().view()
            OnionResponse(info = innerJson, body = jsonBytes)
        }
    }

    private fun processForkInfo(map: Map<*, *>) {
        if (!map.containsKey("hf")) return

        try {
            @Suppress("UNCHECKED_CAST")
            val currentHf = map["hf"] as? List<Int> ?: return

            if (currentHf.size >= 2) {
                val hf = currentHf[0]
                val sf = currentHf[1]
                val newForkInfo = ForkInfo(hf, sf)

//                snodeDirectory.get().updateForkInfo(newForkInfo)
            }
        } catch (e: Exception) {
            Log.w("Onion Request", "Failed to parse fork info", e)
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

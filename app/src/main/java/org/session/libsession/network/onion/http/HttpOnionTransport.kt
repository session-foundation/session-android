package org.session.libsession.network.onion.http

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
    private fun mapPathHttpError(
        node: Snode,
        ex: HTTP.HTTPRequestFailedException,
        path: Path,
        destination: OnionDestination
    ): OnionError {
        val message = ex.body

        val statusCode = ex.statusCode

        //Log.w("Onion Request", "Got an HTTP error. Status: $statusCode, message: $message", ex)

        // Special onion path error: "Next node not found: <ed25519>"
        //todo ONION do we also need to care for "Next node is currently unreachable: or "<cpr error message string>"
        val prefix = "Next node not found: "
        if (message != null && message.startsWith(prefix)){
            val failedPk = message.removePrefix(prefix)

            // The missing Snode is the destination
            if( failedPk == (destination as? OnionDestination.SnodeDestination)?.snode?.publicKeySet?.ed25519Key){
                return OnionError.DestinationUnreachable(
                    status = ErrorStatus(
                        code = statusCode,
                        message = message,
                        body = null
                    ),
                    destination = destination
                )
            } else { // the missing snode is along the path
                return OnionError.IntermediateNodeUnreachable(
                    reportingNode = node,
                    failedPublicKey = failedPk,
                    status = ErrorStatus(
                        code = statusCode,
                        message = message,
                        body = null
                    ),
                    destination = destination
                )
            }
        }

        // check for the case where the SERVER destination no longer exists.
        // The rule is:
        // - the destination is a ServerDestination
        // - the status code is 502 or 504
        // - the message contains the server's destination url
        if(destination is OnionDestination.ServerDestination
            && statusCode in 500..504
            && message?.contains(destination.host) == true ){
            return OnionError.DestinationUnreachable(
                status = ErrorStatus(
                    code = statusCode,
                    message = message,
                    body = null
                ),
                destination = destination
            )
        }

        return OnionError.PathError(
            node = node,
            status = ErrorStatus(
                code = statusCode,
                message = message,
                body = null
            ),
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
            Version.V2, Version.V3 -> handleV2V3Response(rawResponse, destinationSymmetricKey, destination)
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
                throw OnionError.DestinationError(destination, ErrorStatus(0, "Error decoding payload"))
            }

            val infoSepIdx = decrypted.indexOfFirst { it == ':'.code.toByte() }
            if (infoSepIdx <= 1) throw OnionError.DestinationError(destination, ErrorStatus(0, "Error decoding payload"))

            val infoLenSlice = decrypted.slice(1 until infoSepIdx)
            val infoLength = infoLenSlice.toByteArray().toString(Charsets.US_ASCII).toIntOrNull()
                ?: throw OnionError.DestinationError(destination, ErrorStatus(0, "Error decoding payload"))

            val infoStartIndex = "l$infoLength".length + 1
            val infoEndIndex = infoStartIndex + infoLength
            if (infoEndIndex > decrypted.size) throw OnionError.DestinationError(destination, ErrorStatus(0, "Error decoding payload"))

            val infoSlice = decrypted.view(infoStartIndex until infoEndIndex)
            val responseInfo = JsonUtil.fromJson(infoSlice, Map::class.java) as Map<*, *>

            val statusCode = responseInfo["code"].toString().toInt()

            if (statusCode !in 200..299) {
                Log.i("Onion Request", "Successful response decrypted, but non-2xx status code: $statusCode")

                // Optional "body" part for some server errors (notably 400)
                val bodySlice = decrypted.getBody(infoLength, infoEndIndex)

                throw
                    OnionError.DestinationError(
                        status = ErrorStatus(
                            code = statusCode,
                            message = responseInfo["message"]?.toString(),
                            body = bodySlice
                        ),
                        destination = destination
                    )
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
            throw OnionError.DestinationError(destination, ErrorStatus(code = 0, message = "Decrypted payload is not valid JSON", null))
        }

        val statusCode: Int =
            (innerJson["status_code"] as? Number)?.toInt()
                ?: (innerJson["status"] as? Number)?.toInt()
                ?: throw OnionError.DestinationError(destination, ErrorStatus(code = 0, message = "Missing status code in V2/V3 response", null))

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
                    throw OnionError.DestinationError(destination, ErrorStatus(code = 0, message = "Failed to parse body string as JSON", null))
                }

                val parsedMap = parsed as? Map<*, *>
                    ?: throw OnionError.DestinationError(destination, ErrorStatus(code = 0, message = "Parsed body was not a JSON object", null))

                processForkInfo(parsedMap)
                parsedMap
            }

            else -> {
                throw OnionError.DestinationError(destination, ErrorStatus(code = 0, message = "Unexpected body type: ${bodyObj::class.java}", null))
            }
        }

        fun extractMessage(from: Map<*, *>): String? =
            (from["result"] as? String) ?: (from["message"] as? String)

        if (statusCode !in 200..299) {
            val errorMap = (normalizedBody as? Map<*, *>) ?: innerJson
            throw OnionError.DestinationError(
                status = ErrorStatus(
                    code = statusCode,
                    message = extractMessage(errorMap),
                    body = JsonUtil.toJson(errorMap).toByteArray().view(),
                ),
                destination = destination
            )
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

                snodeDirectory.get().updateForkInfo(newForkInfo)
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

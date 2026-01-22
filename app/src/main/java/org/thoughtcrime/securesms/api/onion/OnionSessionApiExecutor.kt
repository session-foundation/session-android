package org.thoughtcrime.securesms.api.onion

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.IOException
import okio.utf8Size
import org.session.libsession.network.NetworkErrorManager
import org.session.libsession.network.NetworkFailureContext
import org.session.libsession.network.NetworkFailureKey
import org.session.libsession.network.model.ErrorStatus
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.OnionBuilder
import org.session.libsession.network.onion.OnionRequestEncryption
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.onion.Version
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.utilities.AESGCM
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.toResponseBody
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiExecutor
import org.thoughtcrime.securesms.api.SessionApiRequest
import org.thoughtcrime.securesms.api.SessionApiResponse
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.http.HttpApiExecutor
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import okhttp3.Request as HttpRequest

const val SEED_SNODE_HTTP_EXECUTOR_NAME = "seed_snode_executor"
const val REGULAR_SNODE_HTTP_EXECUTOR_NAME = "regular_snode_executor"

class OnionSessionApiExecutor @Inject constructor(
    @param:Named(SEED_SNODE_HTTP_EXECUTOR_NAME)
    private val seedSnodeHttpApiExecutor: Provider<HttpApiExecutor>,

    @param:Named(REGULAR_SNODE_HTTP_EXECUTOR_NAME)
    private val regularSnodeHttpApiExecutor: Provider<HttpApiExecutor>,

    private val snodeDirectory: Provider<SnodeDirectory>,
    private val pathManager: PathManager,
    private val json: Json,
    private val networkErrorManager: NetworkErrorManager,
) : SessionApiExecutor {
    override suspend fun send(
        ctx: ApiExecutorContext,
        req: SessionApiRequest<*>
    ): SessionApiResponse {
        val path = pathManager.getPath()
        val onionRequestVersion: Version
        val onionDestination: OnionDestination
        val payload: ByteArray

        when (req) {
            is SessionApiRequest.SnodeJsonRPC -> {
                onionRequestVersion = Version.V3
                onionDestination = OnionDestination.SnodeDestination(req.snode)
                payload = json.encodeToString(JsonObject(
                    mapOf(
                        "method" to json.parseToJsonElement(req.methodName),
                        "params" to req.params
                    )
                )).toByteArray()
            }

            is SessionApiRequest.HttpServerRequest -> {
                onionRequestVersion = Version.V4
                onionDestination = OnionDestination.ServerDestination(
                    host = req.request.url.host,
                    port = req.request.url.port,
                    x25519PublicKey = req.serverX25519PubKeyHex,
                    scheme = req.request.url.scheme,
                    target = req.request.url.encodedPath.removePrefix("/"),
                )
                payload = generateV4HttpServerPayload(req.request)
            }
        }

        val builtOnion = OnionBuilder.build(
            path = path,
            destination = onionDestination,
            payload = payload,
            version = onionRequestVersion
        )

        val body = OnionRequestEncryption.encode(
            ciphertext = builtOnion.ciphertext,
            json = mapOf(
                "ephemeral_key" to builtOnion.ephemeralPublicKey.toHexString(),
            )
        )

        val guard = builtOnion.guard
        val normalizedBaseUrl = buildString {
            append(guard.address)
            if ((guard.address.startsWith("http://") && guard.port == 80) ||
                (guard.address.startsWith("https://") && guard.port == 443)) {
                // default port, do not append
            } else {
                append(':')
                append(guard.port)
            }
        }.lowercase()

        val url = "$normalizedBaseUrl/onion_req/v2".toHttpUrl()

        val executor = if (snodeDirectory.get().seedNodePool.contains(normalizedBaseUrl)) {
            seedSnodeHttpApiExecutor.get()
        } else {
            regularSnodeHttpApiExecutor.get()
        }

        val result = runCatching {
            executor.send(
                ctx = ctx,
                req = HttpRequest.Builder()
                    .url(url)
                    .post(
                        body.toRequestBody(
                            contentType = "application/json; charset=utf-8".toMediaType()
                        )
                    )
                    .build()
            )
        }

        return when {
            result.isSuccess && result.getOrThrow().isSuccessful -> {
                when (onionRequestVersion) {
                    Version.V3 -> handleV3Response(result.getOrThrow().body, builtOnion)
                    Version.V4 -> handleV4Response(result.getOrThrow().body, builtOnion)
                }
            }

            else -> {
                val error = if (result.isSuccess) {
                    mapHttpError(
                        path = path,
                        httpResponseCode = result.getOrThrow().code,
                        httpResponseBody = withContext(Dispatchers.IO) {
                            result.getOrThrow().body.string()
                        },
                        destination = onionDestination,
                    )
                } else if (result.exceptionOrNull() is IOException) {
                    OnionError.GuardUnreachable(
                        guard = path.first(),
                        destination = onionDestination,
                        cause = result.exceptionOrNull()!!
                    )
                } else {
                    OnionError.Unknown(
                        destination = onionDestination,
                        cause = result.exceptionOrNull()!!
                    )
                }

                val failureContext = ctx.getOrPut(NetworkFailureKey) {
                    NetworkFailureContext(
                        path = path,
                        destination = onionDestination,
                        targetSnode = (req as? SessionApiRequest.SnodeJsonRPC)?.snode,
                        publicKey = (req as? SessionApiRequest.SnodeJsonRPC)?.snode?.publicKeySet?.x25519Key, //TODO: Check if it's the right key
                        previousError = null
                    )
                }

                val decision = networkErrorManager.onFailure(
                    error = error,
                    ctx = failureContext
                )

                ctx.set(NetworkFailureKey, failureContext.copy(previousError = error))

                throw ErrorWithFailureDecision(
                    cause = error,
                    failureDecision = decision
                )
            }
        }
    }

    /**
     * Errors thrown by the guard / path hop BEFORE we get an onion-encrypted reply.
     */
    @VisibleForTesting
    internal fun mapHttpError(
        httpResponseCode: Int,
        httpResponseBody: String?,
        path: Path,
        destination: OnionDestination,
    ): OnionError {
        val guardSnode = path.first()

        // ---- 502: hop can't find/contact next hop ----
        val nextNodeNotFound = "Next node not found: "
        val nextNodeUnreachable = "Next node is currently unreachable: "

        // to extract the  key from the error message
        fun parseNextHopPk(msg: String): String? = when {
            msg.startsWith(nextNodeNotFound) -> msg.removePrefix(nextNodeNotFound).trim()
            msg.startsWith(nextNodeUnreachable) -> msg.removePrefix(nextNodeUnreachable).trim()
            else -> null
        }

        val failedPk = httpResponseBody?.let(::parseNextHopPk)
        if (httpResponseCode == 502 && failedPk != null) {
            val destPk =
                (destination as? OnionDestination.SnodeDestination)?.snode?.publicKeySet?.ed25519Key

            return if (destPk != null && failedPk == destPk) {
                OnionError.DestinationUnreachable(
                    status = ErrorStatus(
                        code = httpResponseCode,
                        message = httpResponseBody,
                        body = null
                    ),
                    destination = destination
                )
            } else {
                OnionError.IntermediateNodeUnreachable(
                    reportingNode = guardSnode,
                    failedPublicKey = failedPk,
                    status = ErrorStatus(
                        code = httpResponseCode,
                        message = httpResponseBody,
                        body = null
                    ),
                    destination = destination
                )
            }
        }

        // ---- 503: "Snode not ready" ----
        if (httpResponseCode == 503) {
            val snodeNotReadyPrefix = "Snode not ready: "
            val snodeNotReady = httpResponseBody?.startsWith(snodeNotReadyPrefix) == true

            val guardNotReady =
                httpResponseBody?.startsWith("Service node is not ready:") == true ||
                        httpResponseBody?.startsWith("Server busy, try again later") == true

            if (guardNotReady) {
                return OnionError.SnodeNotReady(
                    failedPublicKey = path.first().publicKeySet?.x25519Key,
                    status = ErrorStatus(
                        code = httpResponseCode,
                        message = httpResponseBody,
                        body = null
                    ),
                    destination = destination,
                )
            } else if (snodeNotReady) {
                val pk = httpResponseBody.removePrefix(snodeNotReadyPrefix).trim()
                return OnionError.SnodeNotReady(
                    failedPublicKey = pk,
                    status = ErrorStatus(
                        code = httpResponseCode,
                        message = httpResponseBody,
                        body = null
                    ),
                    destination = destination
                )
            }
        }

        // ---- 504: timeouts along path ----
        if (httpResponseCode == 504 && httpResponseBody?.contains(
                "Request time out",
                ignoreCase = true
            ) == true
        ) {
            return OnionError.PathTimedOut(
                status = ErrorStatus(
                    code = httpResponseCode,
                    message = httpResponseBody,
                    body = null
                ),
                destination = destination
            )
        }

        // ---- 500: invalid response from next hop ----
        //todo ONION currently we have no handling of 5xx for snode destination as it's unclear how to best handle them
        if (httpResponseCode == 500 && httpResponseBody?.contains(
                "Invalid response from snode",
                ignoreCase = true
            ) == true
        ) {
            return OnionError.InvalidHopResponse(
                node = guardSnode,
                status = ErrorStatus(
                    code = httpResponseCode,
                    message = httpResponseBody,
                    body = null
                ),
                destination = destination
            )
        }

        // Default: generic path error
        return OnionError.PathError(
            node = guardSnode,
            status = ErrorStatus(code = httpResponseCode, message = httpResponseBody, body = null),
            destination = destination
        )
    }

    private fun generateV4HttpServerPayload(req: Request): ByteArray {
        val meta = json.encodeToString(V4RequestMeta(req))
        val body = req.body?.let { body ->
            Buffer().also(body::writeTo)
        }

        return ByteArrayOutputStream().use { outputStream ->
            outputStream.writer().use { writer ->
                writer.write("l${meta.utf8Size()}:")
                writer.write(meta)

                if (body != null) {
                    writer.write("${body.size}:")
                    writer.flush() // Flush before writing raw bytes
                    body.writeTo(outputStream)
                }

                writer.write("e")
            }

            outputStream.toByteArray()
        }
    }

    @Serializable
    private class V4RequestMeta(
        val endpoint: String,
        val method: String,
        val headers: Map<String, String>,
    ) {
        constructor(request: Request)
                : this(
            endpoint = buildString {
                append(request.url.encodedPath)
                if (request.url.encodedQuery != null) {
                    append("?")
                    append(request.url.encodedQuery)
                }
            },
            method = request.method,
            headers = request.headers.toMap()
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun handleV4Response(
        body: ResponseBody,
        builtOnion: OnionBuilder.BuiltOnion
    ): SessionApiResponse.HttpServerResponse {
        val bodyBytes = withContext(Dispatchers.IO) {
            body.bytes() // Read all bytes into memory
        }

        val decrypted = AESGCM.decrypt(
            bodyBytes,
            symmetricKey = builtOnion.destinationSymmetricKey
        )

        val infoSepIdx = decrypted.indexOfFirst { it == ':'.code.toByte() }
        check(infoSepIdx > 1) {
            "Error decoding payload"
        }

        val infoLenSlice = decrypted.slice(1 until infoSepIdx)
        val infoLength = infoLenSlice.toByteArray().toString(Charsets.US_ASCII).toInt()

        val infoStartIndex = "l$infoLength".length + 1
        val infoEndIndex = infoStartIndex + infoLength
        check(infoEndIndex <= decrypted.size) {
            "Error decoding payload"
        }

        val info: V4ResponseInfo = decrypted.view(infoStartIndex until infoEndIndex)
            .inputStream()
            .use(json::decodeFromStream)

        val bodySlice = decrypted.getBody(infoLength, infoEndIndex)

        val builder = Response.Builder()
            .code(info.code)

        if (!info.headers.isNullOrEmpty()) {
            info.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        }

        builder.body(bodySlice.toResponseBody())

        return SessionApiResponse.HttpServerResponse(builder.build())
    }

    private fun ByteArray.getBody(infoLength: Int, infoEndIndex: Int): ByteArraySlice {
        val infoLengthStringLength = infoLength.toString().length
        if (size <= infoLength + infoLengthStringLength + 2) return ByteArraySlice.EMPTY

        val dataSlice = view(infoEndIndex + 1 until size - 1)
        val dataSepIdx = dataSlice.asList().indexOfFirst { it.toInt() == ':'.code }
        if (dataSepIdx == -1) return ByteArraySlice.EMPTY

        return dataSlice.view(dataSepIdx + 1 until dataSlice.len)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun handleV3Response(
        body: ResponseBody,
        builtOnion: OnionBuilder.BuiltOnion
    ): SessionApiResponse.JsonRPCResponse {
        val ivAndCipherText = Base64.decode(withContext(Dispatchers.IO) {
            body.bytes()
        })

        val response: V3Response =
            AESGCM.decrypt(ivAndCipherText, symmetricKey = builtOnion.destinationSymmetricKey)
                .inputStream()
                .use(json::decodeFromStream)

        return SessionApiResponse.JsonRPCResponse(
            code = response.code,
            bodyAsText = response.body,
            bodyAsJson = runCatching {
                json.decodeFromString<JsonElement>(response.body)
            }.getOrNull()
        )
    }

    @Serializable
    private class V3Response(
        val code: Int,
        val body: String,
    )

    @Serializable
    private class V4ResponseInfo(
        val code: Int,
        val headers: Map<String, String>? = emptyMap()
    )

}
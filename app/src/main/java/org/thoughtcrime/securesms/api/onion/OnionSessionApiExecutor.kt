package org.thoughtcrime.securesms.api.onion

import androidx.annotation.VisibleForTesting
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.IOException
import okio.utf8Size
import org.session.libsession.network.model.ErrorStatus
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.OnionBuilder
import org.session.libsession.network.onion.OnionRequestEncryption
import org.session.libsession.network.onion.OnionRequestVersion
import org.session.libsession.network.onion.PathManager
import org.session.libsession.utilities.AESGCM
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiExecutor
import org.thoughtcrime.securesms.api.SessionApiRequest
import org.thoughtcrime.securesms.api.SessionApiResponse
import org.thoughtcrime.securesms.api.direct.DirectSessionApiExecutor
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.http.HttpApiExecutor
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Provider


/**
 * An implementation of [SessionApiExecutor] that routes requests over onion paths.
 *
 * Responsibilities:
 * - Pick a path (via [PathManager])
 * - Build onion request (via [OnionBuilder])
 * - Encrypt onion request payload (via [OnionRequestEncryption])
 * - Send request via [HttpApiExecutor]
 * - Handle exception specific to onion requests (via [OnionSessionApiErrorManager])
 *
 * To override the path used for a specific request, set [OnionPathOverridesKey] in the
 * [ApiExecutorContext] passed to [send].
 *
 * Note: requests to seed nodes are sent directly, as onion routing to seed nodes is currently not
 * supported.
 */
class OnionSessionApiExecutor @Inject constructor(
    private val httpApiExecutor: HttpApiExecutor,
    private val pathManager: PathManager,
    private val json: Json,
    private val onionSessionApiErrorManager: OnionSessionApiErrorManager,
    private val directSessionApiExecutor: Provider<DirectSessionApiExecutor>,
    private val onionRequestEncryption: OnionRequestEncryption,
    private val onionBuilder: OnionBuilder,
) : SessionApiExecutor {
    override suspend fun send(
        ctx: ApiExecutorContext,
        req: SessionApiRequest<*>
    ): SessionApiResponse {
        val onionRequestVersion: OnionRequestVersion
        val onionDestination: OnionDestination
        val payload: ByteArray

        when (req) {
            is SessionApiRequest.SnodeJsonRPC -> {
                onionRequestVersion = OnionRequestVersion.V3
                onionDestination = OnionDestination.SnodeDestination(req.snode)
                payload = json.encodeToString(req.request).toByteArray()
            }

            is SessionApiRequest.SeedNodeJsonRPC -> {
                // A request to seed node can't be routed via onion request atm so we will
                // go direct.
                return directSessionApiExecutor.get().send(ctx, req)
            }

            is SessionApiRequest.HttpServerRequest -> {
                onionRequestVersion = OnionRequestVersion.V4
                onionDestination = OnionDestination.ServerDestination(
                    host = req.request.url.host,
                    port = req.request.url.port,
                    x25519PublicKey = req.serverX25519PubKeyHex,
                    scheme = req.request.url.scheme,
                    target = OnionRequestVersion.V4.value,
                )
                payload = generateV4HttpServerPayload(req.request)
            }
        }

        val pathOverrides = ctx.get(OnionPathOverridesKey)
        val path = pathOverrides ?: pathManager.getPath()

        val builtOnion = try {
            onionBuilder.build(
                path = path,
                destination = onionDestination,
                payload = payload,
                onionRequestVersion = onionRequestVersion
            )
        } catch (e: Exception) {
            throw OnionError.EncodingError(
                destination = onionDestination,
                cause = e
            )
        }

        val body = try {
            onionRequestEncryption.encode(
                ciphertext = builtOnion.ciphertext,
                json = mapOf(
                    "ephemeral_key" to builtOnion.ephemeralPublicKey.toHexString(),
                )
            )
        } catch (e: Exception) {
            throw OnionError.EncodingError(
                destination = onionDestination,
                cause = e
            )
        }

        val guard = builtOnion.guard
        val url = "${guard.address}:${guard.port}/onion_req/v2".toHttpUrl()

        val httpRequest = HttpRequest(
            url = url,
            method = "POST",
            headers = mapOf(),
            body = HttpBody.Bytes(body)
        )

        val result = runCatching {
            httpApiExecutor.send(
                ctx = ctx,
                req = httpRequest
            )
        }

        return if (result.isSuccess && result.getOrThrow().statusCode in 200..299) {
            when (onionRequestVersion) {
                OnionRequestVersion.V3 -> handleV3Response(result.getOrThrow().body, builtOnion)
                OnionRequestVersion.V4 -> handleV4Response(
                    body = result.getOrThrow().body,
                    builtOnion = builtOnion
                )
            }
        }
        else {
            val error = if (result.isSuccess) {
                mapHttpError(
                    path = path,
                    httpResponseCode = result.getOrThrow().statusCode,
                    httpResponseBody = result.getOrThrow().body.toText(),
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

            throw ErrorWithFailureDecision(
                cause = error,
                failureDecision = onionSessionApiErrorManager.onFailure(
                    error = error,
                    path = path,
                    // When path overrides are used, we skip any path punishment logic,
                    // as that path is not selected by us.
                    shouldPunishPath = pathOverrides == null,
                )
            )
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
                    offendingSnode = path.firstOrNull { it.ed25519Key == failedPk },
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
                    offendingSnode = path.first(),
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
                    offendingSnode = path.firstOrNull { it.ed25519Key == pk },
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

        //todo ONION currently we have no handling of 5xx for snode destination as it's unclear how to best handle them

        // Default: generic path error
        return OnionError.PathError(
            guardNode = guardSnode,
            status = ErrorStatus(code = httpResponseCode, message = httpResponseBody, body = null),
            destination = destination
        )
    }

    private fun generateV4HttpServerPayload(req: HttpRequest): ByteArray {
        val meta = json.encodeToString(V4RequestMeta(req))

        return ByteArrayOutputStream().use { outputStream ->
            outputStream.writer().use { writer ->
                writer.write("l${meta.utf8Size()}:")
                writer.write(meta)

                if (req.body != null) {
                    writer.write("${req.body.byteLength}:")
                    writer.flush() // Flush before writing raw bytes
                    req.body.asInputStream().use { it.copyTo(outputStream) }
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
        constructor(request: HttpRequest)
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
    private fun handleV4Response(
        body: HttpBody,
        builtOnion: OnionBuilder.BuiltOnion
    ): SessionApiResponse.HttpServerResponse {
        val decrypted = AESGCM.decrypt(
            body.toBytes(),
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

        return SessionApiResponse.HttpServerResponse(
            HttpResponse(
                statusCode = info.code,
                headers = info.headers.orEmpty(),
                body = HttpBody.ByteSlice(bodySlice)
            )
        )
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
    private fun handleV3Response(
        body: HttpBody,
        builtOnion: OnionBuilder.BuiltOnion
    ): SessionApiResponse.JsonRPCResponse {
        val ivAndCipherText = Base64.decode(body.toBytes())

        val response: V3Response =
            AESGCM.decrypt(ivAndCipherText, symmetricKey = builtOnion.destinationSymmetricKey)
                .inputStream()
                .use(json::decodeFromStream)

        return SessionApiResponse.JsonRPCResponse(
            code = response.status,
            bodyAsJson = runCatching {
                json.decodeFromString<JsonElement>(response.body)
            }.getOrNull(),
            bodyAsText = response.body,
        )
    }

    @Serializable
    private class V3Response(
        val status: Int,
        val body: String,
    )

    @Serializable
    private class V4ResponseInfo(
        val code: Int,
        val headers: Map<String, String>? = emptyMap()
    )

    object OnionPathOverridesKey : ApiExecutorContext.Key<Path>
}
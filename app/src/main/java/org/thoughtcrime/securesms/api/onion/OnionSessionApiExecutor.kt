package org.thoughtcrime.securesms.api.onion

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.IOException
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
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionAPIExecutor
import org.thoughtcrime.securesms.api.SessionAPIRequest
import org.thoughtcrime.securesms.api.SessionAPIResponse
import org.thoughtcrime.securesms.api.SessionAPIResponseBody
import org.thoughtcrime.securesms.api.SessionDestination
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.http.HttpApiExecutor
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import kotlin.text.removePrefix
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
) : SessionAPIExecutor {
    override suspend fun send(
        ctx: ApiExecutorContext,
        dest: SessionDestination,
        req: SessionAPIRequest
    ): SessionAPIResponse {
        val path = pathManager.getPath()
        val onionRequestVersion = when (dest) {
            is SessionDestination.Snode -> Version.V3
            is SessionDestination.HttpServer -> Version.V4
        }

        val onionDestination = when (dest) {
            is SessionDestination.Snode -> OnionDestination.SnodeDestination(dest.snode)
            is SessionDestination.HttpServer -> OnionDestination.ServerDestination(
                host = dest.url.host,
                port = dest.url.port,
                target = dest.url.encodedPath,
                x25519PublicKey = dest.x25519PubKeyHex,
                scheme = dest.url.scheme,
            )
        }

        val builtOnion = OnionBuilder.build(
            path = path,
            destination = onionDestination,
            payload = req,
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

        val response = runCatching {
            executor.send(
                ctx = ctx,
                dest = url,
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
            response.isSuccess && response.getOrThrow().isSuccessful -> {
                when (onionRequestVersion) {
                    Version.V3 -> handleV3Response(response.getOrThrow().body, builtOnion)
                    Version.V4 -> handleV4Response(response.getOrThrow().body, builtOnion)
                }
            }

            else -> {
                val error = if (response.isSuccess) {
                    mapHttpError(
                        path = path,
                        httpResponseCode = response.getOrThrow().code,
                        httpResponseBody = withContext(Dispatchers.IO) {
                            response.getOrThrow().body.string()
                        },
                        destination = onionDestination,
                    )
                } else if (response.exceptionOrNull() is IOException) {
                    OnionError.GuardUnreachable(
                        guard = path.first(),
                        destination = onionDestination,
                        cause = response.exceptionOrNull()!!
                    )
                } else {
                    OnionError.Unknown(
                        destination = onionDestination,
                        cause = response.exceptionOrNull()!!
                    )
                }

                val failureContext = ctx.getOrPut(NetworkFailureKey) {
                    NetworkFailureContext(
                        path = path,
                        destination = onionDestination,
                        targetSnode = (dest as? SessionDestination.Snode)?.snode,
                        publicKey = (dest as? SessionDestination.Snode)?.snode?.publicKeySet?.x25519Key,
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


    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun handleV4Response(
        body: ResponseBody,
        builtOnion: OnionBuilder.BuiltOnion
    ): SessionAPIResponse {
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

        return SessionAPIResponse(
            code = info.code,
            body = SessionAPIResponseBody.Bytes(bodySlice)
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
    private suspend fun handleV3Response(
        body: ResponseBody,
        builtOnion: OnionBuilder.BuiltOnion
    ): SessionAPIResponse {
        val ivAndCipherText = Base64.decode(withContext(Dispatchers.IO) {
            body.bytes()
        })

        val response: V3Response =
            AESGCM.decrypt(ivAndCipherText, symmetricKey = builtOnion.destinationSymmetricKey)
                .inputStream()
                .use(json::decodeFromStream)

        return SessionAPIResponse(
            code = response.status,
            body = SessionAPIResponseBody.Text(response.body)
        )
    }

    @Serializable
    private class V3Response(
        @SerialName("status_code")
        private val _statusCode: Int? = null,

        @SerialName("status")
        private val _status: Int? = null,

        val body: String,
    ) {
        init {
            check(_status != null || _statusCode != null) {
                "Response must contain either 'status' or 'status_code'"
            }
        }

        val status: Int
            get() = _status ?: _statusCode!!
    }

    @Serializable
    private class V4ResponseInfo(
        val code: Int,
        val message: String? = null
    )

}
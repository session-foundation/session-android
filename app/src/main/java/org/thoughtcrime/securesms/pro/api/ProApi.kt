package org.thoughtcrime.securesms.pro.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.session.libsession.network.ServerClientErrorManager
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.server.ServerApi
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import org.thoughtcrime.securesms.pro.ProBackendConfig
import javax.inject.Inject

/**
 * Represents a generic API request to the Pro backend.
 *
 * @param ErrorStatus The type of error status returned by the API.
 * @param Res The type of the expected response.
 */
abstract class ProApi<ErrorStatus, Res>(private val deps: ProApiDependencies)
    : ServerApi<ProApiResponse<Res, ErrorStatus>>(deps.errorManager) {
    /**
     * The endpoint (path) for this API request, e.g. "v1/pro/payments"
     */
    abstract val endpoint: String

    abstract val responseDeserializer: DeserializationStrategy<Res>

    abstract fun convertErrorStatus(status: Int): ErrorStatus

    abstract fun buildJsonBody(): String

    override fun buildRequest(
        baseUrl: String,
        x25519PubKeyHex: String
    ): HttpRequest {
        return HttpRequest(
            method = "POST",
            url = "$baseUrl/$endpoint".toHttpUrl(),
            headers = mapOf(
                "Content-Type" to "application/json"
            ),
            body = HttpBody.Text(buildJsonBody())
        )
    }

    @Suppress("OPT_IN_USAGE")
    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): ProApiResponse<Res, ErrorStatus> {
        val rawResp: RawProApiResponse = response.body
            .asInputStream()
            .use { deps.json.decodeFromStream(it) }

        return if (rawResp.status == 0) {
            val data = deps.json.decodeFromJsonElement(
                responseDeserializer,
                requireNotNull(rawResp.result) {
                    "Expected 'result' field to be present on successful response"
                })
            ProApiResponse.Success(data)
        } else {
            ProApiResponse.Failure(
                status = convertErrorStatus(rawResp.status),
                errors = rawResp.errors.orEmpty()
            )
        }
    }

    class ProApiDependencies @Inject constructor(
        val errorManager: ServerClientErrorManager,
        val json: Json,
    )

    @Serializable
    private data class RawProApiResponse(
        val status: Int,
        val result: JsonElement? = null,
        val errors: List<String>? = null,
    )
}


/**
 * Represents the response from a Pro API request.
 *
 * @param Res The type of the successful response data.
 */
sealed interface ProApiResponse<out Res, out Status> {
    data class Success<T>(val data: T) : ProApiResponse<T, Nothing>
    data class Failure<S>(val status: S, val errors: List<String>) : ProApiResponse<Nothing, S>
}

fun <T> ProApiResponse<T, *>.successOrThrow(): T {
    return when (this) {
        is ProApiResponse.Success -> this.data
        is ProApiResponse.Failure -> throw RuntimeException("Fail with status = $status, errors = $errors")
    }
}

fun <Resp: Any, ErrorStatus> ServerApiRequest(
    proBackendConfig: ProBackendConfig,
    api: ProApi<ErrorStatus, Resp>
): ServerApiRequest<ProApiResponse<Resp, ErrorStatus>> {
    return ServerApiRequest<ProApiResponse<Resp, ErrorStatus>>(
        serverBaseUrl = proBackendConfig.url.toString(),
        serverX25519PubKeyHex = proBackendConfig.x25519PubKeyHex,
        api = api
    )
}
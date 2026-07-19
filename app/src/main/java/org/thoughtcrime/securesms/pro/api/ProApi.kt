package org.thoughtcrime.securesms.pro.api

import kotlinx.serialization.json.Json
import network.loki.messenger.libsession_util.pro.ProRequest
import network.loki.messenger.libsession_util.pro.ProResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.thoughtcrime.securesms.api.server.ServerApiErrorManager
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
abstract class ProApi<ErrorStatus, Res : ProResponse>(private val deps: ProApiDependencies)
    : ServerApi<ProApiResponse<Res, ErrorStatus>>(deps.errorManager) {

    /** Builds the request (endpoint + signed JSON body) via libsession. */
    abstract fun buildProRequest(): ProRequest

    /** Parses the raw response body JSON into a typed struct via libsession. */
    abstract fun parseResponse(json: String): Res

    abstract fun convertErrorStatus(status: Int): ErrorStatus

    override fun buildRequest(
        baseUrl: String,
        x25519PubKeyHex: String
    ): HttpRequest {
        val request = buildProRequest()
        return HttpRequest(
            method = "POST",
            url = "$baseUrl/${request.endpoint}".toHttpUrl(),
            headers = mapOf(
                "Content-Type" to "application/json"
            ),
            body = HttpBody.Text(request.body)
        )
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): ProApiResponse<Res, ErrorStatus> {
        // libsession owns response parsing: hand it the raw body and get a typed struct back.
        val bodyJson = response.body.asInputStream().use { it.readBytes().decodeToString() }
        val parsed = parseResponse(bodyJson)

        return if (parsed.header.isSuccess) {
            ProApiResponse.Success(parsed)
        } else {
            ProApiResponse.Failure(
                status = convertErrorStatus(parsed.header.status),
                errors = parsed.header.errors
            )
        }
    }

    class ProApiDependencies @Inject constructor(
        val errorManager: ServerApiErrorManager,
        val json: Json,
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
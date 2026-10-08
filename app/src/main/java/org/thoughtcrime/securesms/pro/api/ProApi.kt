package org.thoughtcrime.securesms.pro.api

import kotlinx.serialization.json.Json
import network.loki.messenger.libsession_util.pro.ProRequest
import network.loki.messenger.libsession_util.pro.ProResponse
import network.loki.messenger.libsession_util.pro.ProResponseStatus
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
 * @param Res The type of the expected response.
 */
abstract class ProApi<Res : ProResponse>(private val deps: ProApiDependencies)
    : ServerApi<ProApiResponse<Res>>(deps.errorManager) {

    /** Builds the request (endpoint + signed body) via libsession. */
    abstract fun buildProRequest(): ProRequest

    /** Parses the raw response body into a typed struct via libsession. */
    abstract fun parseResponse(json: String): Res

    override fun buildRequest(
        baseUrl: String,
        x25519PubKeyHex: String
    ): HttpRequest {
        val request = buildProRequest()
        return HttpRequest(
            method = "POST",
            // baseUrl is an HttpUrl.toString(), which normalizes a bare host to a trailing "/"; trim it
            // so we don't emit a doubled slash (e.g. //get_pro_status) in the inner request path.
            url = "${baseUrl.trimEnd('/')}/${request.endpoint}".toHttpUrl(),
            // Content-Type comes from libsession (the wire format is its contract with the backend); we relay it
            headers = mapOf(
                "Content-Type" to request.contentType
            ),
            body = HttpBody.Text(request.body)
        )
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): ProApiResponse<Res> {
        // libsession owns response parsing: hand it the raw body and get a typed struct back.
        val bodyJson = response.body.asInputStream().use { it.readBytes().decodeToString() }
        val parsed = parseResponse(bodyJson)

        return if (parsed.header.isSuccess) {
            ProApiResponse.Success(parsed)
        } else {
            ProApiResponse.Failure(
                error = ProApiError(
                    status = parsed.header.status,
                    errorCode = parsed.header.errorCode,
                    error = parsed.header.error,
                ),
                parsed = parsed,
            )
        }
    }

    class ProApiDependencies @Inject constructor(
        val errorManager: ServerApiErrorManager,
        val json: Json,
    )
}

/**
 * A failed Pro backend response (§5). [status] is [ProResponseStatus.Fail] (client input /
 * precondition) or [ProResponseStatus.Error] (backend fault, retryable). [errorCode] is the machine slug
 * (see [ProErrorCode]; null if libsession supplied none) that the UI maps to a localized string; [error]
 * is an English diagnostic — NOT user-facing (only shown when a slug has no translation), always safe to log.
 */
data class ProApiError(
    val status: ProResponseStatus,
    val errorCode: String?,
    val error: String?,
) {
    /** A backend fault is safe to retry as-is; so is a not-yet-visible payment (a transient race). */
    val isRetryable: Boolean
        get() = status == ProResponseStatus.Error || errorCode == ProErrorCode.UNKNOWN_PAYMENT
}

/**
 * Canonical machine-readable error-code slugs (wire spec §5.1). The set is open/additive — an unrecognized
 * slug is expected and handled by [status]-level logic + the diagnostic fallback, never a hard failure.
 */
object ProErrorCode {
    const val INVALID_REQUEST = "invalid_request"
    const val BAD_SIGNATURE = "bad_signature"
    const val STALE_REQUEST = "stale_request"
    const val UNKNOWN_PAYMENT = "unknown_payment"
    const val SUBSCRIPTION_EXPIRED = "subscription_expired"
    const val NOT_SUBSCRIBED = "not_subscribed"
    const val REVOKED = "revoked"
    const val INTERNAL_ERROR = "internal_error"
}

/**
 * Represents the response from a Pro API request.
 *
 * @param Res The type of the successful response data.
 */
sealed interface ProApiResponse<out Res> {
    data class Success<T>(val data: T) : ProApiResponse<T>

    /**
     * A failure still carries the [parsed] body, because some error slugs are informative rather than
     * merely negative: `subscription_expired` returns the account expiry, grace period and renewing flag
     * precisely so a client can persist them.
     *
     * Reaching here means the ENVELOPE parsed — a body that could not be parsed throws before this — so
     * [parsed] is a real struct rather than defaults built from nothing. That distinction is what makes it
     * safe to write config from a failure at all, and it is only safe **per slug**: libsession populates
     * those fields for `subscription_expired` and leaves them at their defaults otherwise, and the defaults
     * are indistinguishable from genuine zeroes. Gate any write on the slug.
     */
    data class Failure<T>(val error: ProApiError, val parsed: T) : ProApiResponse<T>
}

fun <T> ProApiResponse<T>.successOrThrow(): T {
    return when (this) {
        is ProApiResponse.Success -> this.data
        is ProApiResponse.Failure -> throw RuntimeException(
            "Pro API failed: status=${error.status}, code=${error.errorCode}, error=${error.error}"
        )
    }
}

fun <Resp : ProResponse> ServerApiRequest(
    proBackendConfig: ProBackendConfig,
    api: ProApi<Resp>
): ServerApiRequest<ProApiResponse<Resp>> {
    return ServerApiRequest<ProApiResponse<Resp>>(
        serverBaseUrl = proBackendConfig.url.toString(),
        serverX25519PubKeyHex = proBackendConfig.x25519PubKeyHex,
        api = api
    )
}

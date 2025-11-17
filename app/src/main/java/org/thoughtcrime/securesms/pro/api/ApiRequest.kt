package org.thoughtcrime.securesms.pro.api

import kotlinx.serialization.DeserializationStrategy

/**
 * Represents a generic API request to the Pro backend.
 *
 * @param ErrorStatus The type of error status returned by the API.
 * @param Res The type of the expected response.
 */
interface ApiRequest<out ErrorStatus, Res> {
    /**
     * The endpoint (path) for this API request, e.g. "v1/pro/payments"
     */
    val endpoint: String

    val responseDeserializer: DeserializationStrategy<Res>

    fun convertErrorStatus(status: Int): ErrorStatus

    fun buildJsonBody(): String
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
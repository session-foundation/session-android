package org.thoughtcrime.securesms.pro.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.utilities.await

/**
 * Represents a generic API request to the Pro backend.
 *
 * @param Status The type of the status returned by the API.
 * @param Res The type of the expected response.
 */
interface ApiRequest<out Status, Res> {
    /**
     * The endpoint (path) for this API request, e.g. "v1/pro/payments"
     */
    val endpoint: String

    val responseDeserializer: DeserializationStrategy<Res>

    fun convertStatus(status: Int): Status

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
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
import org.session.libsession.snode.OnionRequestAPI.sendOnionRequest
import org.session.libsession.snode.utilities.await
import javax.inject.Inject

class ProApiExecutor @Inject constructor(
    private val json: Json
) {
    @Serializable
    private data class RawProApiResponse(
        val status: Int,
        val result: JsonElement? = null,
        val errors: List<String>? = null,
    ) {
        fun <Res> toProApiResponse(
            deserializer: DeserializationStrategy<Res>,
            json: Json
        ): ProApiResponse<Res, Int> {
            return if (status == 0) {
                val data = json.decodeFromJsonElement(deserializer, requireNotNull(result) {
                    "Expected 'result' field to be present on successful response"
                })
                ProApiResponse.Success(data)
            } else {
                ProApiResponse.Failure(
                    status = status,
                    errors = errors.orEmpty()
                )
            }
        }
    }


    /**
     * Executes the given [ApiRequest] against the specified server using an onion request.
     *
     * @return A [ProApiResponse] containing either the successful response data or error information.
     * Note that network errors, json deserialization will throw exceptions and are not represented
     * in the [ProApiResponse]: you must catch and handle those separately.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <Status, Res> executeRequest(
        serverUrl: HttpUrl = "https://pro-backend-dev.getsession.org".toHttpUrl(),
        serverX25519PubKeyHex: String = "920b81e9bf1a06e70814432668c61487d6fdbe13faaee3b09ebc56223061f140",
        request: ApiRequest<Status, Res>
    ): ProApiResponse<Res, Status> {
        val rawResp = sendOnionRequest(
            request = Request.Builder()
                .url(serverUrl.resolve(request.endpoint)!!)
                .post(
                    request.buildJsonBody().toRequestBody(
                        "application/json".toMediaType()
                    )
                )
                .build(),
            server = serverUrl.host,
            x25519PublicKey = serverX25519PubKeyHex
        ).await().body!!.inputStream().use {
            json.decodeFromStream<RawProApiResponse>(it)
        }

        return if (rawResp.status == 0) {
            val data = json.decodeFromJsonElement(
                request.responseDeserializer,
                requireNotNull(rawResp.result) {
                    "Expected 'result' field to be present on successful response"
                })
            ProApiResponse.Success(data)
        } else {
            ProApiResponse.Failure(
                status = request.convertStatus(rawResp.status),
                errors = rawResp.errors.orEmpty()
            )
        }
    }
}
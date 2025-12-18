package org.thoughtcrime.securesms.pro.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.session.libsession.network.SessionNetwork
import org.thoughtcrime.securesms.pro.ProBackendConfig
import javax.inject.Inject
import javax.inject.Provider

class ProApiExecutor @Inject constructor(
    private val json: Json,
    private val proConfigProvider: Provider<ProBackendConfig>,
    private val sessionNetwork: SessionNetwork,
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
        request: ApiRequest<Status, Res>
    ): ProApiResponse<Res, Status> {
        val config = proConfigProvider.get()

        val rawResp = sessionNetwork.sendToServer(
            request = Request.Builder()
                .url(config.url.resolve(request.endpoint)!!)
                .post(
                    request.buildJsonBody().toRequestBody(
                        "application/json".toMediaType()
                    )
                )
                .build(),
            serverBaseUrl = config.url.host,
            x25519PublicKey = config.x25519PubKeyHex
        ).body!!.inputStream().use {
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
                status = request.convertErrorStatus(rawResp.status),
                errors = rawResp.errors.orEmpty()
            )
        }
    }
}
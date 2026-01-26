package org.thoughtcrime.securesms.notifications

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.session.libsession.network.ServerClientErrorManager
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.batch.Batcher
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.server.JsonServerApi
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import javax.inject.Inject

class PushApiBatcher @Inject constructor(
    private val json: Json,
    private val serverClientErrorManager: ServerClientErrorManager,
) : Batcher<ServerApiRequest<*>, Any, JsonElement> {
    override fun batchKey(req: ServerApiRequest<*>): Any? {
        return when (req.api) {
            is PushRegisterApi -> "PushRegisterApi"
            is PushUnregisterApi -> "PushUnregisterApi"
            else -> null
        }
    }

    override fun transformRequestForBatching(
        ctx: ApiExecutorContext,
        req: ServerApiRequest<*>
    ): JsonElement {
        return when (req.api) {
            is PushRegisterApi -> req.api.buildJsonPayload()
            is PushUnregisterApi -> req.api.buildJsonPayload()
            else -> error("Unsupported API for batching: ${req.api::class.java}")
        }
    }

    override fun constructBatchRequest(
        firstRequest: ServerApiRequest<*>,
        intermediateRequests: List<JsonElement>
    ): ServerApiRequest<*> {
        firstRequest.api as JsonServerApi<*>

        return ServerApiRequest(
            serverBaseUrl = firstRequest.serverBaseUrl,
            serverX25519PubKeyHex = firstRequest.serverX25519PubKeyHex,
            api = object : JsonServerApi<JsonArray>(json, serverClientErrorManager) {
                override val httpMethod: String get() = firstRequest.api.httpMethod
                override val httpEndpoint: String get() = firstRequest.api.httpEndpoint
                override val responseSerializer: DeserializationStrategy<JsonArray>
                    get() = JsonArray.serializer()

                override fun buildJsonPayload() = JsonArray(intermediateRequests)
            }
        )
    }

    override suspend fun deconstructBatchResponse(
        requests: List<Pair<ApiExecutorContext, ServerApiRequest<*>>>,
        response: Any
    ): List<Result<Any>> {
        response as JsonArray

        return List(requests.size) { index ->
            val respElement = response[index]
            val (ctx, req) = requests[index]

            runCatching {
                req.api.processResponse(
                    executorContext = ctx,
                    baseUrl = req.serverBaseUrl,
                    response = HttpResponse(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = HttpBody.Text(json.encodeToString(respElement))
                    )
                )
            }
        }
    }
}
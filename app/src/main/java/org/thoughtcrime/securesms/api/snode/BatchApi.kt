package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.session.libsession.network.SnodeClientErrorManager
import org.session.libsession.snode.model.BatchResponse

class BatchApi @AssistedInject constructor(
    @Assisted val requests: List<SnodeApi<*>>,
    private val json: Json,
    snodeClientErrorManager: SnodeClientErrorManager,
) : AbstractSnodeApi<BatchApi.Response>(
    snodeClientErrorManager = snodeClientErrorManager,
) {
    override val methodName: String get() = "batch"

    override fun buildParams(): JsonElement {
        return json.encodeToJsonElement(
            Request(
                requests = requests.map { req ->
                    RequestItem(
                        method = req.methodName,
                        params = req.buildParams()
                    )
                }
            )
        )
    }

    @Serializable
    private class RequestItem(
        val method: String,
        val params: JsonElement
    )

    @Serializable
    private class Request(
        val requests: List<RequestItem>
    )

    class Response(
        val requestParams: List<JsonElement>,
        val responses: List<BatchResponse.Item>,
    ) {
        init {
            check(requestParams.size == responses.size) {
                "Mismatched batch response size: expected ${requestParams.size}, got ${responses.size}"
            }
        }
    }

    override fun deserializeSuccessResponse(requestParams: JsonElement, body: JsonElement): Response {
         val items = json.decodeFromJsonElement(BatchResponse.serializer(), body).results
        return Response(
            requestParams = ((requestParams as JsonObject)["requests"]) as JsonArray,
            responses = items
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(requests: List<SnodeApi<*>>): BatchApi
    }
}
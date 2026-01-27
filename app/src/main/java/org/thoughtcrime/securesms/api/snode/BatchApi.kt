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
import org.session.libsession.snode.model.BatchResponse

class BatchApi @AssistedInject constructor(
    @Assisted val requests: List<SnodeJsonRequest>,
    private val json: Json,
    errorManager: SnodeApiErrorManager,
) : AbstractSnodeApi<BatchApi.Response>(errorManager) {
    override val methodName: String get() = "batch"

    override fun buildParams(): JsonElement {
        return json.encodeToJsonElement(Request(requests))
    }

    @Serializable
    private class Request(
        val requests: List<SnodeJsonRequest>
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
    abstract class Factory {
        abstract fun create(requests: List<SnodeJsonRequest>): BatchApi

        fun createFromApis(apis: List<SnodeApi<*>>): BatchApi {
            return create(apis.map { it.buildRequest() })
        }
    }
}
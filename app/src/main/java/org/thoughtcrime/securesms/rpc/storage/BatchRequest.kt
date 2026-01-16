package org.thoughtcrime.securesms.rpc.storage

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsession.snode.model.BatchResponse

class BatchRequest @AssistedInject constructor(
    @Assisted val requests: List<StorageServiceRequest<*>>,
    private val json: Json,
) : AbstractStorageServiceRequest<BatchResponse>() {

    override val methodName: String
        get() = "batch"

    override fun buildParams(): JsonObject {
        return JsonObject(
            mapOf(
                "requests" to JsonArray(requests.map { req ->
                    JsonObject(
                        mapOf(
                            "method" to JsonPrimitive(req.methodName),
                            "params" to req.buildParams()
                        )
                    )
                })
            )
        )
    }

    override fun deserializeSuccessResponse(body: JsonElement): BatchResponse {
        return json.decodeFromJsonElement(BatchResponse.serializer(), body)
    }

    @AssistedFactory
    interface Factory {
        fun create(requests: List<StorageServiceRequest<*>>): BatchRequest
    }
}
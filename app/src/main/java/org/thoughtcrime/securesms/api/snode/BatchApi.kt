package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsession.network.SnodeClientErrorManager
import org.session.libsession.snode.model.BatchResponse

class BatchApi @AssistedInject constructor(
    @Assisted val requests: List<SnodeApi<*>>,
    private val json: Json,
    snodeClientErrorManager: SnodeClientErrorManager,
) : AbstractSnodeApi<BatchResponse>(
    snodeClientErrorManager = snodeClientErrorManager,
) {

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
        fun create(requests: List<SnodeApi<*>>): BatchApi
    }
}
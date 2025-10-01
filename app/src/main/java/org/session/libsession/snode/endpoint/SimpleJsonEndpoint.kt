package org.session.libsession.snode.endpoint

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

abstract class SimpleJsonEndpoint<Req, Res> : Endpoint<Req, Res> {
    abstract val requestSerializer: SerializationStrategy<Req>
    abstract val responseDeserializer: DeserializationStrategy<Res>

    override fun serializeRequest(
        json: Json,
        request: Req
    ) = json.encodeToJsonElement(requestSerializer, request)

    override fun deserializeResponse(
        json: Json,
        response: JsonElement
    ) = json.decodeFromJsonElement(responseDeserializer, response)
}
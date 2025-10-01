package org.session.libsession.snode.endpoint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

sealed interface Endpoint<Request, Response> {
    fun serializeRequest(
        json: Json,
        request: Request
    ): JsonElement

    fun deserializeResponse(
        json: Json,
        response: JsonElement
    ): Response

    val methodName: String

    fun batchKey(request: Request): String?

    companion object {
        // A convenient constant for endpoints that are always
        // batchable among each other
        const val BATCH_KEY_ALWAYS = "endpoint"
    }
}
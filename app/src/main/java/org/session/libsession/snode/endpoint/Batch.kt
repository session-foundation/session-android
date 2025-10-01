package org.session.libsession.snode.endpoint

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement

class Batch : SimpleJsonEndpoint<Batch.Request, Batch.Response>() {
    override val requestSerializer: SerializationStrategy<Request>
        get() = Request.serializer()

    override val responseDeserializer: DeserializationStrategy<Response>
        get() = Response.serializer()

    override val methodName: String
        get() = "batch"

    override fun batchKey(request: Request): String? = null

    @Serializable
    class Request(val requests: List<Item>) {

        @Serializable
        class Item(val method: String, val params: JsonElement)
    }


    @Serializable
    class Response(val results: List<Result>) {
        @Serializable
        class Result(val code: Int, val body: JsonElement) {
            val isSuccessful: Boolean
                get() = code in 200..299

            val isServerError: Boolean
                get() = code in 500..599

            val isSnodeNoLongerPartOfSwarm: Boolean
                get() = code == 421
        }

        class Error(val result: Result): Exception("Batch request failed with code ${result.code}, body = ${result.body}")
    }
}
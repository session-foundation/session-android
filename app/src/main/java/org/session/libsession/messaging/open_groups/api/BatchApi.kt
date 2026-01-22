package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer

class BatchApi @AssistedInject constructor(
    @Assisted private val items: List<CommunityApi<*>>,
    deps: CommunityApiDependencies,
) : CommunityApi<List<BatchApi.BatchResponseItem>>(deps) {
    override val room: String?
        get() = null

    override val requiresSigning: Boolean
        get() = false

    override val httpMethod: String
        get() = "POST"

    override val responseDeserializer: DeserializationStrategy<List<BatchResponseItem>>
        get() = ListSerializer(BatchResponseItem.serializer())

    override val httpEndpoint: String
        get() = "/batch"

    override fun buildRequestBody(serverBaseUrl: String, x25519PubKeyHex: String): Pair<MediaType, ByteArray> {
        return "application/json".toMediaType() to json.encodeToString(JsonArray(items.map {
            val req = it.buildRequest(serverBaseUrl, x25519PubKeyHex)
            JsonObject(
                buildMap {
                    put("method", JsonPrimitive(req.method))
                    put("endpoint", JsonPrimitive(it.httpEndpoint))
                    put("headers", JsonObject(req.headers.associate { (k, v) -> k to JsonPrimitive(v) }) )

                    if (req.body != null && req.header("Content-Type")?.startsWith("application/json") == true) {
                        put("body", JsonPrimitive(req.body!!.readText()))
                    }
                }
            )
        })).toByteArray()
    }

    private fun RequestBody.readText(): String {
        val buffer = Buffer()
        this.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Serializable
    class BatchResponseItem(
        val code: Int,
        val headers: Map<String, String>,
        val body: JsonElement?
    ) {
        fun toHttpResponse(): Response {
            TODO()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(items: List<CommunityApi<*>>): BatchApi
    }
}
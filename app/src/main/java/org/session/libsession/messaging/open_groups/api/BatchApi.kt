package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import org.session.libsignal.utilities.Base64
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse

class BatchApi @AssistedInject constructor(
    @Assisted private val items: List<CommunityApi<*>>,
    deps: CommunityApiDependencies,
) : CommunityApi<List<BatchApi.BatchResponseItem>>(deps) {
    override val room: String?
        get() = null

    override val requiresSigning: Boolean by lazy {
        items.any { it.requiresSigning }
    }

    override val httpMethod: String
        get() = "POST"

    override val responseDeserializer: DeserializationStrategy<List<BatchResponseItem>>
        get() = ListSerializer(BatchResponseItem.serializer())

    override val httpEndpoint: String
        get() = "/batch"

    override fun buildRequestBody(
        serverBaseUrl: String,
        x25519PubKeyHex: String
    ): Pair<MediaType, HttpBody> {
        val requestItems = items.map {
            BatchRequestItem(httpRequest = it.buildRequest(serverBaseUrl, x25519PubKeyHex), json)
        }

        val text = json.encodeToString(requestItems)

        return "application/json".toMediaType() to HttpBody.Text(text)
    }

    @Serializable
    private class BatchRequestItem(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val json: JsonElement? = null,
        val b64: String? = null
    ) {
        init {
            check(json == null || b64 == null) { "Only one of 'json' or 'b64' can be set" }
        }

        constructor(httpRequest: HttpRequest, json: Json) : this(
            method = httpRequest.method,
            path = httpRequest.url.encodedPath,
            headers = httpRequest.headers.toMap(),
            json = if (httpRequest.isJsonBody) {
                @Suppress("OPT_IN_USAGE")
                httpRequest.body?.asInputStream()?.use(json::decodeFromStream)
            } else {
                null
            },
            b64 = if (!httpRequest.isJsonBody) {
                httpRequest.body?.toBytes()?.let(Base64::encodeBytes)
            } else {
                null
            }
        )
    }


    @Serializable
    class BatchResponseItem(
        val code: Int,
        val headers: Map<String, String>,
        val body: JsonElement?
    ) {
        fun toHttpResponse(json: Json): HttpResponse {
            return HttpResponse(
                statusCode = code,
                headers = headers,
                body = body?.let {
                    HttpBody.Text(json.encodeToString(it))
                } ?: HttpBody.empty()
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(items: List<CommunityApi<*>>): BatchApi
    }

    companion object {
        private val HttpRequest.isJsonBody: Boolean
            get() {
                return getHeader("Content-Type")?.startsWith("application/json", ignoreCase = true) == true
            }
    }
}
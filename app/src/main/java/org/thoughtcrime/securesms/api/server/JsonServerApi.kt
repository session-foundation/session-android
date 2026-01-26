package org.thoughtcrime.securesms.api.server

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.session.libsession.network.ServerClientErrorManager
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse

abstract class JsonServerApi<RespType>(
    protected val json: Json,
    errorManager: ServerClientErrorManager
): ServerApi<RespType>(errorManager) {
    abstract val httpMethod: String
    abstract val httpEndpoint: String
    abstract val responseSerializer: DeserializationStrategy<RespType>

    abstract fun buildJsonPayload(): JsonElement?

    open fun transformResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        resp: RespType): RespType = resp

    final override fun buildRequest(
        baseUrl: String,
        x25519PubKeyHex: String
    ): HttpRequest {
        val body = buildJsonPayload()?.let {
            HttpBody.Text(json.encodeToString( it))
        }

        check(!httpMethod.equals("GET", ignoreCase = true) || body == null) {
            "GET requests cannot have a body"
        }

        return HttpRequest(
            url = "$baseUrl/$httpEndpoint".toHttpUrl(),
            method = httpMethod,
            headers = if (body != null) mapOf(
                "Content-Type" to "application/json"
            ) else emptyMap(),
            body = body
        )
    }

    @Suppress("OPT_IN_USAGE")
    final override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): RespType {
        val rep = response.body.asInputStream().use {
            json.decodeFromStream(responseSerializer, it)
        }

        return transformResponse(executorContext, baseUrl, rep)
    }
}
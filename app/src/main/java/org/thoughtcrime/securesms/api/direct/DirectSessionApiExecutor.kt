package org.thoughtcrime.securesms.api.direct

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiExecutor
import org.thoughtcrime.securesms.api.SessionApiRequest
import org.thoughtcrime.securesms.api.SessionApiResponse
import org.thoughtcrime.securesms.api.http.HttpApiExecutor
import org.thoughtcrime.securesms.api.http.HttpRequest
import javax.inject.Inject

/**
 * A [SessionApiExecutor] that directly sends requests using an underlying [HttpApiExecutor].
 */
class DirectSessionApiExecutor @Inject constructor(
    private val httpApiExecutor: HttpApiExecutor,
    private val json: Json,
) : SessionApiExecutor {
    override suspend fun send(
        ctx: ApiExecutorContext,
        req: SessionApiRequest<*>
    ): SessionApiResponse {
        val underlyingRequest = when (req) {
            is SessionApiRequest.SnodeJsonRPC -> {
                HttpRequest.createFromJson(
                    url = "${req.snode.address}:${req.snode.port}/storage_rpc/v1".toHttpUrl(),
                    method = "POST",
                    jsonText = json.encodeToString(req.request)
                )
            }

            is SessionApiRequest.SeedNodeJsonRPC -> {
                HttpRequest.createFromJson(
                    url = req.seedNodeUrl.resolve("/json_rpc")!!,
                    method = "POST",
                    jsonText = json.encodeToString(req.request)
                )
            }

            is SessionApiRequest.HttpServerRequest -> req.request
        }

        val httpResponse = httpApiExecutor.send(ctx, underlyingRequest)

        return when (req) {
            is SessionApiRequest.SeedNodeJsonRPC -> {
                @Suppress("OPT_IN_USAGE")
                val bodyAsJson: SeedNodeJsonRPCResponse = httpResponse.body.asInputStream()
                    .use(json::decodeFromStream)

                SessionApiResponse.JsonRPCResponse(
                    code = httpResponse.statusCode,
                    bodyAsText = null,
                    bodyAsJson = bodyAsJson.result,
                )
            }

            is SessionApiRequest.SnodeJsonRPC -> {
                val bodyAsText = httpResponse.body.toText()
                SessionApiResponse.JsonRPCResponse(
                    code = httpResponse.statusCode,
                    bodyAsText = bodyAsText,
                    bodyAsJson = bodyAsText?.let {
                        runCatching { json.decodeFromString<JsonElement>(it) }
                            .getOrNull()
                    },
                )
            }

            is SessionApiRequest.HttpServerRequest -> SessionApiResponse.HttpServerResponse(httpResponse)
        }
    }

    @Serializable
    private class SeedNodeJsonRPCResponse(val result: JsonElement)
}
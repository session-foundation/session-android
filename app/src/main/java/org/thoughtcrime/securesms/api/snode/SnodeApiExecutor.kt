package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutor
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiExecutor
import org.thoughtcrime.securesms.api.SessionAPIResponseBody
import org.thoughtcrime.securesms.api.SessionDestination
import java.io.ByteArrayOutputStream
import javax.inject.Inject

typealias SnodeApiExecutor = ApiExecutor<Snode, SnodeApi<*>, SnodeApiResponse>

class SnodeApiExecutorImpl @Inject constructor(
    private val executor: SessionApiExecutor,
    private val json: Json,
) : SnodeApiExecutor {
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun send(
        ctx: ApiExecutorContext,
        dest: Snode,
        req: SnodeApi<*>
    ): SnodeApiResponse {
        @Suppress("OPT_IN_USAGE") val response = executor.send(
            ctx = ctx,
            dest = SessionDestination.Snode(dest),
            req = ByteArrayOutputStream().use {
                json.encodeToStream(req.buildRequest(), it)
                it.toByteArray()
            })

        return req.handleResponse(
            ctx = ctx,
            snode = dest,
            code = response.code,
            body = when (val body = response.body) {
                is SessionAPIResponseBody.Text -> json.decodeFromString<JsonElement>(body.text)
                is SessionAPIResponseBody.Bytes -> body.bytes.inputStream()
                    .use(json::decodeFromStream)
            }
        )
    }

    private fun SnodeApi<*>.buildRequest(): JsonObject {
        return JsonObject(
            mapOf("method" to JsonPrimitive(methodName), "params" to buildParams())
        )
    }
}

suspend inline fun <reified Res, Req> SnodeApiExecutor.execute(
    dest: Snode,
    req: Req,
    ctx: ApiExecutorContext = ApiExecutorContext(),
): Res where Res : SnodeApiResponse, Req : SnodeApi<Res> {
    return send(ctx, dest, req) as Res
}
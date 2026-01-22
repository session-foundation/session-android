package org.thoughtcrime.securesms.api

import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import okhttp3.Response
import org.session.libsignal.utilities.Snode


sealed interface SessionApiRequest<ResponseType : SessionApiResponse> {
    data class SnodeJsonRPC(
        val snode: Snode,
        val methodName: String,
        val params: JsonElement,
    ) : SessionApiRequest<SessionApiResponse.JsonRPCResponse>

    data class HttpServerRequest(
        val request: Request,
        val serverX25519PubKeyHex: String
    ) : SessionApiRequest<SessionApiResponse.HttpServerResponse>
}

sealed interface SessionApiResponse {
    class JsonRPCResponse(
        val code: Int,
        val bodyAsText: String,
        val bodyAsJson: JsonElement?,
    ) : SessionApiResponse

    class HttpServerResponse(val response: Response) : SessionApiResponse
}

typealias SessionApiExecutor = ApiExecutor<SessionApiRequest<*>, SessionApiResponse>

suspend inline fun <reified Res, Req> SessionApiExecutor.execute(
    req: Req,
    ctx: ApiExecutorContext = ApiExecutorContext()
): Res where Res : SessionApiResponse, Req : SessionApiRequest<Res> {
    return send(ctx, req) as Res
}
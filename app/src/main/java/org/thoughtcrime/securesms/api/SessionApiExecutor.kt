package org.thoughtcrime.securesms.api

import kotlinx.serialization.json.JsonElement
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.snode.SnodeJsonRequest


sealed interface SessionApiRequest<ResponseType : SessionApiResponse> {
    /**
     * Send a JSON-RPC request to a specific snode.
     */
    data class SnodeJsonRPC(
        val snode: Snode,
        val request: SnodeJsonRequest,
    ) : SessionApiRequest<SessionApiResponse.JsonRPCResponse>

    /**
     * Send/proxy a raw HTTP request to a server.
     */
    data class HttpServerRequest(
        val request: HttpRequest,
        val serverX25519PubKeyHex: String
    ) : SessionApiRequest<SessionApiResponse.HttpServerResponse>
}

sealed interface SessionApiResponse {
    class JsonRPCResponse(
        val code: Int,
        val bodyAsText: String?,
        val bodyAsJson: JsonElement?,
    ) : SessionApiResponse

    class HttpServerResponse(val response: HttpResponse) : SessionApiResponse
}

/**
 * An [ApiExecutor] for sending [SessionApiRequest]s.
 */
typealias SessionApiExecutor = ApiExecutor<SessionApiRequest<*>, SessionApiResponse>

suspend inline fun <reified Res, Req> SessionApiExecutor.execute(
    req: Req,
    ctx: ApiExecutorContext = ApiExecutorContext()
): Res where Res : SessionApiResponse, Req : SessionApiRequest<Res> {
    return send(ctx, req) as Res
}
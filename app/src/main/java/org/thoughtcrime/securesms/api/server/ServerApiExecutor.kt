package org.thoughtcrime.securesms.api.server

import org.session.libsession.messaging.file_server.FileServer
import org.thoughtcrime.securesms.api.ApiExecutor
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiExecutor
import org.thoughtcrime.securesms.api.SessionApiRequest
import org.thoughtcrime.securesms.api.execute
import javax.inject.Inject

class ServerApiRequest<RespType: ServerApiResponse>(
    val serverBaseUrl: String,
    val serverX25519PubKeyHex: String,
    val api: ServerApi<RespType>,
) {
    constructor(fileServer: FileServer, api: ServerApi<RespType>) : this(
        serverBaseUrl = fileServer.url.toString(),
        serverX25519PubKeyHex = fileServer.x25519PubKeyHex,
        api = api,
    )

    override fun toString(): String {
        return "ServerApiRequest(api=${api::class.java.simpleName}, serverBaseUrl='$serverBaseUrl')"
    }
}

typealias ServerApiResponse = Any

typealias ServerApiExecutor = ApiExecutor<ServerApiRequest<*>, ServerApiResponse>

class ServerApiExecutorImpl @Inject constructor(
    private val apiExecutor: SessionApiExecutor,
) : ServerApiExecutor {
    override suspend fun send(
        ctx: ApiExecutorContext,
        req: ServerApiRequest<*>
    ): ServerApiResponse {
        val resp = apiExecutor.execute(
            ctx = ctx,
            req = SessionApiRequest.HttpServerRequest(
                req.api.buildRequest(
                    baseUrl = req.serverBaseUrl,
                    x25519PubKeyHex = req.serverX25519PubKeyHex,
                ),
                serverX25519PubKeyHex = req.serverX25519PubKeyHex,
            )
        )

        return req.api.processResponse(
            executorContext = ctx,
            baseUrl = req.serverBaseUrl,
            response = resp.response
        )
    }
}

suspend inline fun <reified ResponseType : ServerApiResponse> ServerApiExecutor.execute(
    req: ServerApiRequest<ResponseType>,
    ctx: ApiExecutorContext = ApiExecutorContext()
): ResponseType {
    return send(ctx, req) as ResponseType
}
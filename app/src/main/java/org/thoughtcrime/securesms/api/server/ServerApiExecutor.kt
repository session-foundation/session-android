package org.thoughtcrime.securesms.api.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.thoughtcrime.securesms.api.ApiExecutor
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiExecutor
import org.thoughtcrime.securesms.api.SessionDestination

class ServerAddress(
    val url: HttpUrl,
    val x25519PubKey: String
)

class ServerApiExecutor(
    private val apiExecutor: SessionApiExecutor,
) : ApiExecutor<ServerAddress, Request, Response> {
    override suspend fun send(
        ctx: ApiExecutorContext,
        dest: ServerAddress,
        req: Request
    ): Response {
        apiExecutor.send(
            ctx = ctx,
            dest = SessionDestination.HttpServer(
                url = dest.url,
                x25519PubKeyHex = dest.x25519PubKey
            ),
            req = byteArrayOf()
        )
    }

    @Serializable
    private class Payload(
        val endpoint: String,
        val method: String,
        val headers: Map<String, String>,
    )
}
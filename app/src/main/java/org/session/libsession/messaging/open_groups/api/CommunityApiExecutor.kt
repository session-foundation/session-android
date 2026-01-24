package org.session.libsession.messaging.open_groups.api

import org.session.libsession.database.StorageProtocol
import org.thoughtcrime.securesms.api.ApiExecutor
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.server.ServerApi
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import org.thoughtcrime.securesms.api.server.ServerApiResponse
import javax.inject.Inject

data class CommunityApiRequest<Api: CommunityApi<*>>(
    val serverBaseUrl: String,
    val api: Api,
    val serverPubKey: String? = null, // Null to let executor fill in from config data
)

typealias CommunityApiExecutor = ApiExecutor<CommunityApiRequest<*>, ServerApiResponse>

class CommunityApiExecutorImpl @Inject constructor(
    private val serverApiExecutor: ServerApiExecutor,
    private val storage: StorageProtocol
) : CommunityApiExecutor {
    override suspend fun send(
        ctx: ApiExecutorContext,
        req: CommunityApiRequest<*>
    ): ServerApiResponse {
        val x25519PubKey = checkNotNull(
            req.serverPubKey ?: storage.getOpenGroupPublicKey(req.serverBaseUrl)) {
            "No stored x25519 public key for server ${req.serverBaseUrl}"
        }

        @Suppress("UNCHECKED_CAST")
        return serverApiExecutor.send(ctx, ServerApiRequest(
            serverBaseUrl = req.serverBaseUrl,
            serverX25519PubKeyHex = x25519PubKey,
            api = req.api as ServerApi<Any>
        ))
    }
}

suspend inline fun <reified Resp, reified Api: CommunityApi<Resp>> CommunityApiExecutor.execute(
    req: CommunityApiRequest<Api>,
    ctx: ApiExecutorContext = ApiExecutorContext()
): Resp {
    return send(ctx, req) as Resp
}

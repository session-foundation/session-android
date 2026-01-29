package org.thoughtcrime.securesms.api.http

import org.session.libsession.network.snode.SnodeDirectory
import org.thoughtcrime.securesms.api.ApiExecutorContext
import javax.inject.Provider

/**
 * An [HttpApiExecutor] that choose a different underlying executor based on the url:
 * - For seed/official URLs, a certificate-pinned executor is used
 * - For regular snode URLs, a standard executor is used
 */
class SessionHttpApiExecutor(
    private val seedSnodeHttpApiExecutor: HttpApiExecutor,
    private val regularSnodeHttpApiExecutor: HttpApiExecutor,
    private val snodeDirectory: Provider<SnodeDirectory>,
) : HttpApiExecutor {

    override suspend fun send(
        ctx: ApiExecutorContext,
        req: HttpRequest
    ): HttpResponse {
        val normalisedUrl = req.url.newBuilder()
            .encodedPath("/")
            .encodedQuery(null)
            .encodedFragment(null)
            .build()

        return if (snodeDirectory.get().seedNodePool.contains(normalisedUrl)) {
            seedSnodeHttpApiExecutor.send(ctx, req)
        } else {
            regularSnodeHttpApiExecutor.send(ctx, req)
        }
    }
}

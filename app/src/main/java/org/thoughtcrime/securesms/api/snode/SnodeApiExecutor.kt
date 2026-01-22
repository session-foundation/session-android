package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.ExperimentalSerializationApi
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutor
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiExecutor
import org.thoughtcrime.securesms.api.SessionApiRequest
import org.thoughtcrime.securesms.api.execute
import javax.inject.Inject

data class SnodeApiRequest<Resp : SnodeApiResponse>(
    val snode: Snode,
    val api: SnodeApi<Resp>
)

typealias SnodeApiExecutor = ApiExecutor<SnodeApiRequest<*>, SnodeApiResponse>

class SnodeApiExecutorImpl @Inject constructor(
    private val executor: SessionApiExecutor,
) : SnodeApiExecutor {
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun send(
        ctx: ApiExecutorContext,
        req: SnodeApiRequest<*>
    ): SnodeApiResponse {
        val response = executor.execute(
            ctx = ctx,
            req = SessionApiRequest.SnodeJsonRPC(
                snode = req.snode,
                methodName = req.api.methodName,
                params = req.api.buildParams()
            ))

        return req.api.handleResponse(
            ctx = ctx,
            code = response.code,
            body = response.bodyAsJson,
            snode = req.snode
        )
    }
}

suspend inline fun <reified Res, Req> SnodeApiExecutor.execute(
    req: SnodeApiRequest<Res>,
    ctx: ApiExecutorContext = ApiExecutorContext(),
): Res where Res : SnodeApiResponse, Req : SnodeApi<Res> {
    return send(ctx, req) as Res
}
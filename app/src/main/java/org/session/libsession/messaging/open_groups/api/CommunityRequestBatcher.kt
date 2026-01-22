package org.session.libsession.messaging.open_groups.api

import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.BatchApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import javax.inject.Inject

class CommunityRequestBatcher @Inject constructor(
    private val batchApiFactory: BatchApi.Factory,
) : BatchApiExecutor.Batcher<ServerApiRequest<*>, Any> {

    override fun constructBatchRequest(requests: List<Pair<ApiExecutorContext, ServerApiRequest<*>>>): ServerApiRequest<*> {
        return ServerApiRequest(
            serverBaseUrl = requests.first().second.serverBaseUrl,
            serverX25519PubKeyHex = requests.first().second.serverX25519PubKeyHex,
            api = batchApiFactory.create(
                requests.map { it.second.api as CommunityApi<*> }
            )
        )
    }

    override fun batchKey(req: ServerApiRequest<*>): Any? {
        if (req.api is CommunityApi<*>) {
            return req.serverBaseUrl
        }

        return null
    }

    override suspend fun deconstructBatchResponse(
        requests: List<Pair<ApiExecutorContext, ServerApiRequest<*>>>,
        response: Any
    ): List<Result<Any>> {
        @Suppress("UNCHECKED_CAST") val responseList =
            (response as List<BatchApi.BatchResponseItem>)

        check(requests.size == responseList.size) {
            "Mismatched batch response size: expected=${requests.size}, actual=${responseList.size}"
        }

        return requests.mapIndexed { index, (ctx, req) ->
            runCatching {
                req.api.processResponse(
                    executorContext = ctx,
                    response = responseList[index].toHttpResponse(),
                    baseUrl = req.serverBaseUrl,
                )
            }
        }
    }
}
package org.thoughtcrime.securesms.api.snode

import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.BatchApiExecutor
import javax.inject.Inject

class SnodeApiBatcher @Inject constructor(
    private val batchAPIFactory: BatchApi.Factory,
) : BatchApiExecutor.Batcher<SnodeApiRequest<*>, SnodeApiResponse> {
    override fun constructBatchRequest(
        requests: List<Pair<ApiExecutorContext, SnodeApiRequest<*>>>
    ): SnodeApiRequest<*> {
        return SnodeApiRequest(
            snode = requests.first().second.snode,
            api = batchAPIFactory.create(requests.map { it.second.api })
        )
    }

    override fun batchKey(req: SnodeApiRequest<*>): Any? {
        // Shouldn't batch the batch requests themselves
        if (req.api is BatchApi) {
            return null
        }

        return req.snode.ed25519Key
    }

    override suspend fun deconstructBatchResponse(
        requests: List<Pair<ApiExecutorContext, SnodeApiRequest<*>>>,
        response: SnodeApiResponse
    ): List<Result<SnodeApiResponse>> {
        response as BatchApi.Response

        return requests.indices.map { i ->
            val (ctx, request) = requests[i]
            val result = response.responses[i]
            val requestParams = response.requestParams[i]

            runCatching {
                request.api.handleResponse(
                    ctx = ctx,
                    snode = request.snode,
                    code = result.code,
                    body = result.body,
                    requestParams = requestParams,
                )
            }
        }
    }
}
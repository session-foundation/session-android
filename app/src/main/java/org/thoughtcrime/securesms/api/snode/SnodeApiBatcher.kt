package org.thoughtcrime.securesms.api.snode

import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.batch.Batcher
import javax.inject.Inject

class SnodeApiBatcher @Inject constructor(
    private val batchAPIFactory: BatchApi.Factory,
) : Batcher<SnodeApiRequest<*>, SnodeApiResponse, BatchApi.RequestItem> {
    override fun constructBatchRequest(
        firstRequest: SnodeApiRequest<*>,
        intermediateRequests: List<BatchApi.RequestItem>
    ): SnodeApiRequest<*> {
        return SnodeApiRequest(
            snode = firstRequest.snode,
            api = batchAPIFactory.create(intermediateRequests)
        )
    }

    override fun transformRequestForBatching(
        ctx: ApiExecutorContext,
        req: SnodeApiRequest<*>
    ): BatchApi.RequestItem {
        return BatchApi.RequestItem(
            method = req.api.methodName,
            params = req.api.buildParams()
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
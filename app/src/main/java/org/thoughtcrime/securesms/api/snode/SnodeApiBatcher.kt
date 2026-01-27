package org.thoughtcrime.securesms.api.snode

import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.batch.Batcher
import javax.inject.Inject

class SnodeApiBatcher @Inject constructor(
    private val batchAPIFactory: BatchApi.Factory,
) : Batcher<SnodeApiRequest<*>, SnodeApiResponse, SnodeJsonRequest> {
    override fun constructBatchRequest(
        firstRequest: SnodeApiRequest<*>,
        intermediateRequests: List<SnodeJsonRequest>
    ): SnodeApiRequest<*> {
        return SnodeApiRequest(
            snode = firstRequest.snode,
            api = batchAPIFactory.create(intermediateRequests)
        )
    }

    override fun transformRequestForBatching(
        ctx: ApiExecutorContext,
        req: SnodeApiRequest<*>
    ): SnodeJsonRequest {
        return req.api.buildRequest(ctx)
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

            runCatching {
                request.api.handleResponse(
                    ctx = ctx,
                    snode = request.snode,
                    code = result.code,
                    body = result.body,
                )
            }
        }
    }
}
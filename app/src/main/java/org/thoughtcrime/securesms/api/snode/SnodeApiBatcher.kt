package org.thoughtcrime.securesms.api.snode

import org.session.libsession.snode.model.BatchResponse
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

    override fun batchKey(req: SnodeApiRequest<*>): Any {
        return req.snode.ed25519Key
    }

    override suspend fun deconstructBatchResponse(
        requests: List<Pair<ApiExecutorContext, SnodeApiRequest<*>>>,
        response: SnodeApiResponse
    ): List<Result<SnodeApiResponse>> {
        val results = (response as BatchResponse).results
        check(results.size == requests.size) {
            "Mismatched batch response size: expected ${requests.size}, got ${results.size}"
        }

        return requests.indices.map { i ->
            val (ctx, request) = requests[i]
            val result = results[i]

            runCatching {
                request.api.handleResponse(
                    ctx = ctx,
                    snode = request.snode,
                    code = result.code,
                    body = result.body
                )
            }
        }
    }
}
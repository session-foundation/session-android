package org.thoughtcrime.securesms.api.snode

import org.session.libsession.snode.model.BatchResponse
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.BatchApiExecutor
import javax.inject.Inject

class SnodeApiBatcher @Inject constructor(
    private val batchRequestAPIFactory: BatchRequestApi.Factory,
) : BatchApiExecutor.Batcher<Snode, SnodeApi<*>, SnodeApiResponse> {
    override fun constructBatchRequest(
        requests: List<Pair<ApiExecutorContext, SnodeApi<*>>>
    ): SnodeApi<*> {
        return batchRequestAPIFactory.create(requests.map { it.second })
    }

    override suspend fun deconstructBatchResponse(
        dest: Snode,
        requests: List<Pair<ApiExecutorContext, SnodeApi<*>>>,
        response: SnodeApiResponse
    ): List<SnodeApiResponse> {
        val results = (response as BatchResponse).results
        check(results.size == requests.size) {
            "Mismatched batch response size: expected ${requests.size}, got ${results.size}"
        }

        return requests.indices.map { i ->
            val (ctx, request) = requests[i]
            val result = results[i]

            if (!result.isSuccessful) {
                throw BatchResponse.Error(result)
            }

            request.handleResponse(
                ctx = ctx,
                snode = dest,
                code = result.code,
                body = result.body
            )
        }
    }
}
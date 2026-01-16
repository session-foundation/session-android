package org.thoughtcrime.securesms.rpc.storage

import org.session.libsession.snode.model.BatchResponse
import org.thoughtcrime.securesms.rpc.BatchRPCExecutor
import javax.inject.Inject

class StorageServerRequestBatcher @Inject constructor(
    private val batchRequestFactory: BatchRequest.Factory,
) : BatchRPCExecutor.Batcher<StorageServiceRequest<*>, StorageServiceResponse> {
    override fun constructBatchRequest(requests: List<StorageServiceRequest<*>>): StorageServiceRequest<*> {
        return batchRequestFactory.create(requests)
    }

    override fun deconstructBatchResponse(
        requests: List<StorageServiceRequest<*>>,
        response: StorageServiceResponse
    ): List<StorageServiceResponse> {
        val results = (response as BatchResponse).results
        check(results.size == requests.size) {
            "Mismatched batch response size: expected ${requests.size}, got ${results.size}"
        }

        return requests.indices.map { i ->
            val request = requests[i]
            val result = results[i]

            if (!result.isSuccessful) {
                throw BatchResponse.Error(result)
            }

            request.deserializeResponse(, result.body)
        }
    }
}
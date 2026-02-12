package org.thoughtcrime.securesms.api.batch

import org.thoughtcrime.securesms.api.ApiExecutorContext

interface Batcher<Req, Res, T> {

    /**
     * Returns a key that identifies requests that can be batched together.
     */
    fun batchKey(req: Req): Any?

    fun transformRequestForBatching(ctx: ApiExecutorContext, req: Req): T

    /**
     * Constructs a single batch request from the first request and a list of intermediate requests.
     * Note: the construction should not fail because of any problem with individual requests,
     * the (individual) failure should be thrown during the transformation phase.
     */
    fun constructBatchRequest(firstRequest: Req, intermediateRequests: List<T>): Req

    suspend fun deconstructBatchResponse(
        requests: List<Pair<ApiExecutorContext, Req>>,
        response: Res
    ): List<Result<Res>>

    class BatchState<Req>(
        val index: Int,
        val context: ApiExecutorContext,
        val request: Req
    )
}
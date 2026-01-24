package org.session.libsession.messaging.open_groups.api

import kotlinx.serialization.json.Json
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.BatchApiExecutor
import javax.inject.Inject

class CommunityApiBatcher @Inject constructor(
    private val batchApiFactory: BatchApi.Factory,
    private val json: Json,
) : BatchApiExecutor.Batcher<CommunityApiRequest<*>, Any> {

    override fun constructBatchRequest(requests: List<Pair<ApiExecutorContext, CommunityApiRequest<*>>>): CommunityApiRequest<*> {
        return CommunityApiRequest(
            serverBaseUrl = requests.first().second.serverBaseUrl,
            api = batchApiFactory.create(
                requests.map { it.second.api }
            )
        )
    }

    override fun batchKey(req: CommunityApiRequest<*>): Any? {
        // Requests can only be batched if they require signing
        return req.serverBaseUrl.takeIf { req.api.requiresSigning }
    }

    override suspend fun deconstructBatchResponse(
        requests: List<Pair<ApiExecutorContext, CommunityApiRequest<*>>>,
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
                    response = responseList[index].toHttpResponse(json),
                    baseUrl = req.serverBaseUrl,
                )
            }
        }
    }
}
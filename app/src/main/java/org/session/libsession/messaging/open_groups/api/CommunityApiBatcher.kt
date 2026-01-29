package org.session.libsession.messaging.open_groups.api

import kotlinx.serialization.json.Json
import org.session.libsession.database.StorageProtocol
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.batch.Batcher
import javax.inject.Inject

class CommunityApiBatcher @Inject constructor(
    private val batchApiFactory: BatchApi.Factory,
    private val json: Json,
    private val storage: StorageProtocol,
) : Batcher<CommunityApiRequest<*>, Any, BatchApi.BatchRequestItem> {
    override fun transformRequestForBatching(
        ctx: ApiExecutorContext,
        req: CommunityApiRequest<*>
    ): BatchApi.BatchRequestItem {
        val pubKey = requireNotNull(req.serverPubKey ?: storage.getOpenGroupPublicKey(req.serverBaseUrl)) {
            "No stored x25519 public key for server ${req.serverBaseUrl}"
        }

        return BatchApi.BatchRequestItem(
            httpRequest = req.api.buildRequest(
                baseUrl = req.serverBaseUrl,
                x25519PubKeyHex = pubKey
            ),
            json = json
        )
    }

    override fun constructBatchRequest(
        firstRequest: CommunityApiRequest<*>,
        intermediateRequests: List<BatchApi.BatchRequestItem>
    ): CommunityApiRequest<*> {
        return CommunityApiRequest(
            serverBaseUrl = firstRequest.serverBaseUrl,
            serverPubKey = firstRequest.serverPubKey,
            api = batchApiFactory.create(intermediateRequests)
        )
    }

    override fun batchKey(req: CommunityApiRequest<*>): Any? {
        // Shouldn't batch the batch requests themselves
        if (req.api is BatchApi) {
            return null
        }

        // Shouldn't batch APIs that return large amount of data
        if (req.api is CommunityFileDownloadApi) {
            return null
        }

        // Only batch requests that require signing
        if (!req.api.requiresSigning) {
            return null
        }

        return req.serverBaseUrl
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun deconstructBatchResponse(
        requests: List<Pair<ApiExecutorContext, CommunityApiRequest<*>>>,
        response: Any
    ): List<Result<Any>> {
        response as List<BatchApi.BatchResponseItem>

        check(requests.size == response.size) {
            "Mismatched batch response size: expected=${requests.size}, actual=${response.size}"
        }

        return requests.mapIndexed { index, (ctx, req) ->
            runCatching {
                req.api.processResponse(
                    executorContext = ctx,
                    response = response[index].toHttpResponse(json),
                    baseUrl = req.serverBaseUrl,
                )
            }
        }
    }
}
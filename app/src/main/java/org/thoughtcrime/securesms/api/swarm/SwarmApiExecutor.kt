package org.thoughtcrime.securesms.api.swarm

import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutor
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.snode.SnodeApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.api.snode.SnodeApiResponse
import org.thoughtcrime.securesms.api.snode.SnodeNotPartOfSwarmException
import javax.inject.Inject

class SwarmApiRequest<T : SnodeApiResponse>(
    val swarmPubKeyHex: String,
    val api: SnodeApi<T>
)

typealias SwarmApiExecutor = ApiExecutor<SwarmApiRequest<*>, SnodeApiResponse>

suspend inline fun <reified Res, Req> SwarmApiExecutor.execute(
    req: SwarmApiRequest<Res>,
    ctx: ApiExecutorContext = ApiExecutorContext(),
): Res where Res : SnodeApiResponse, Req : SnodeApi<Res> {
    return send(ctx, req) as Res
}

class SwarmApiExecutorImpl @Inject constructor(
    private val snodeApiExecutor: SnodeApiExecutor,
    private val swarmDirectory: SwarmDirectory,
) : SwarmApiExecutor {
    override suspend fun send(
        ctx: ApiExecutorContext,
        req: SwarmApiRequest<*>
    ): SnodeApiResponse {
        val apiContext = ctx.getOrPut(SwarmApiContextKey) {
            SwarmApiContext()
        }

        // Pick a snode from the swarm if we don't already have one cached (across retry)
        val snode = apiContext.snode ?: run {
            val targetSnode = swarmDirectory.getSingleTargetSnode(req.swarmPubKeyHex)
            Log.d(TAG, "Selected snode $targetSnode for publicKey=${req.swarmPubKeyHex}")
            ctx.set(SwarmApiContextKey, SwarmApiContext(snode = targetSnode))
            targetSnode
        }

        try {
            return snodeApiExecutor.send(ctx, SnodeApiRequest(snode, req.api))
        } catch (e: SnodeNotPartOfSwarmException) {
            Log.d(TAG, "Snode $snode is no longer part of swarm for publicKey=${req.swarmPubKeyHex}, updating swarm")
            val updated = swarmDirectory.updateSwarmFromResponse(
                swarmPublicKey = req.swarmPubKeyHex,
                errorResponseBody = e.responseBodyText,
            )

            if (!updated) {
                swarmDirectory.dropSnodeFromSwarmIfNeeded(
                    snode = snode,
                    swarmPublicKey = req.swarmPubKeyHex
                )
            }

            // drop the cached snode so we pick a new one upon retry
            ctx.remove(SwarmApiContextKey)

            throw ErrorWithFailureDecision(
                cause = e,
                failureDecision = FailureDecision.Retry,
            )
        }
    }

    private class SwarmApiContext(
        val snode: Snode? = null
    )

    private object SwarmApiContextKey : ApiExecutorContext.Key<SwarmApiContext>

    companion object {
        private const val TAG = "SwarmApiExecutor"
    }
}


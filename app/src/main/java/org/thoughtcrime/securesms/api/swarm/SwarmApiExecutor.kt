package org.thoughtcrime.securesms.api.swarm

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutor
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.error.UnhandledStatusCodeException
import org.thoughtcrime.securesms.api.snode.SnodeApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.api.snode.SnodeApiResponse

class SwarmApiRequest<T : SnodeApiResponse>(
    val swarmPubKeyHex: String,
    val api: SnodeApi<T>,

    /**
     * When set, this snode will be used for the swarm request. If the snode is later found
     * to be not part of the swarm, it will be removed from our swarm storage, and the executor
     * will not try to pick a different one for retries unless this key is removed from the context.
     */
    val swarmNodeOverride: Snode? = null,
)

/**
 * An [ApiExecutor] that routes [SnodeApi]s to a swarm of snodes, handling snode selection
 * and removal of snodes that are no longer part of the swarm.
 */
typealias SwarmApiExecutor = ApiExecutor<SwarmApiRequest<*>, SnodeApiResponse>

suspend inline fun <reified Res, Req> SwarmApiExecutor.execute(
    req: SwarmApiRequest<Res>,
    ctx: ApiExecutorContext = ApiExecutorContext(),
): Res where Res : SnodeApiResponse, Req : SnodeApi<Res> {
    return send(ctx, req) as Res
}

/**
 * Default implementation of [SwarmApiExecutor].
 */
class SwarmApiExecutorImpl @AssistedInject constructor(
    @Assisted private val snodeApiExecutor: SnodeApiExecutor,
    private val swarmDirectory: SwarmDirectory,
    private val swarmSnodeSelector: SwarmSnodeSelector,
) : SwarmApiExecutor {
    override suspend fun send(
        ctx: ApiExecutorContext,
        req: SwarmApiRequest<*>
    ): SnodeApiResponse {
        val lastUsedSnode = ctx.get(LastUsedSnodeKey)

        // Pick a snode from the swarm if we don't already have one cached (across retry)
        val snode = req.swarmNodeOverride ?: lastUsedSnode ?: run {
            val targetSnode = swarmSnodeSelector.selectSnode(req.swarmPubKeyHex)
            Log.d(TAG, "Selected snode $targetSnode for publicKey=${req.swarmPubKeyHex}")
            ctx.set(LastUsedSnodeKey, targetSnode)
            targetSnode
        }

        try {
            return snodeApiExecutor.send(ctx, SnodeApiRequest(snode, req.api))
        } catch (e: UnhandledStatusCodeException) {
            if (e.code == 421) {
                Log.d(
                    TAG,
                    "Snode $snode is no longer part of swarm for publicKey=${req.swarmPubKeyHex}, updating swarm"
                )
                val updated = swarmDirectory.updateSwarmFromResponse(
                    swarmPublicKey = req.swarmPubKeyHex,
                    errorResponseBody = e.bodyText,
                )

                if (!updated) {
                    swarmDirectory.dropSnodeFromSwarmIfNeeded(
                        snode = snode,
                        swarmPublicKey = req.swarmPubKeyHex
                    )
                }

                // drop the cached snode so we pick a new one upon retry
                ctx.remove(LastUsedSnodeKey)

                throw ErrorWithFailureDecision(
                    cause = RuntimeException("Snode $snode dropped from swarm"),
                    failureDecision = if (req.swarmNodeOverride == null) FailureDecision.Retry else FailureDecision.Fail,
                )
            } else {
                throw e
            }
        }
    }

    /**
     * Stores the last used snode for the swarm request, to be reused across retries.
     */
    private object LastUsedSnodeKey : ApiExecutorContext.Key<Snode>

    @AssistedFactory
    interface Factory {
        fun create(snodeApiExecutor: SnodeApiExecutor): SwarmApiExecutorImpl
    }

    companion object {
        private const val TAG = "SwarmApiExecutor"
    }
}


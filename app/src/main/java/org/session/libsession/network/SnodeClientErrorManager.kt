package org.session.libsession.network

import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class SnodeClientErrorManager @Inject constructor(
    private val pathManager: PathManager,
    private val swarmDirectory: SwarmDirectory,
    private val snodeClock: SnodeClock,
) {

    suspend fun handleUnderlyingException(e: Throwable, targetSnode: Snode, swarmPublicKey: String): FailureDecision? {
        if (e is OnionError.DestinationUnreachable) {
            // in the case of Snode destination being unreachable, we should remove that snode
            // from the pool and swarm (if pubkey is available)
            // handleBadSnode will handle removing the snode from the paths/pool/swarm and clean up the strikes
            // if needed
            pathManager.handleBadSnode(snode = ctx.targetSnode, swarmPublicKey = ctx.publicKey, forceRemove = true)
            return FailureDecision.Retry
        }

        return null
    }

    suspend fun onFailure(code: Int?, bodyText: String?, ctx: SnodeClientFailureContext): FailureDecision {
        // 406 is 'Clock out of sync' for a snode destination
        if (code == 406) {
            // if this is the first time we got a COS, retry, since we should have resynced the clock
            Log.w("Onion Request", "Clock out of sync (code: $code) for destination snode ${ctx.targetSnode.address} - Local Snode clock at ${snodeClock.currentTime()} - First time? ${ctx.previousError == null}")
            if(ctx.previousError == null){
                // reset the clock
                val resync = runCatching {
                    snodeClock.resyncClock()
                }.getOrDefault(false)

                // only retry if we were able to resync the clock
                return if (resync) FailureDecision.Retry else FailureDecision.Fail(RuntimeException("Failed to resync clock after receiving 406 from snode") )
            } else {
                // if we already got a COS, and syncing the clock wasn't enough
                // we should consider the destination snode faulty. Drop from pool and swarm swarm and retry
                // handleBadSnode will handle removing the snode from the paths/pool/swarm and clean up the strikes
                // if needed
                pathManager.handleBadSnode(snode = ctx.targetSnode, swarmPublicKey = ctx.swarmPublicKey, forceRemove = true)
                return FailureDecision.Retry
            }
        }

        // 421: snode isn't associated with pubkey anymore -> update swarm / invalidate -> retry
        if (code == 421) {
            val publicKey = ctx.swarmPublicKey
            val targetSnode = ctx.targetSnode

            val updated = if (publicKey != null) {
                Log.w("Onion Request", "Got 421 with an associated public key. Update Swarm.")
                swarmDirectory.updateSwarmFromResponse(
                    swarmPublicKey = publicKey,
                    errorResponseBody = bodyText?.toByteArray()?.view()
                )
            } else {
                Log.w("Onion Request", "Got 421 without an associated public key.")
                false
            }

            if (!updated && publicKey != null) {
                swarmDirectory.dropSnodeFromSwarmIfNeeded(targetSnode, publicKey)
            }

            return FailureDecision.Retry
        }

        // Unparseable data: 502 + "oxend returned unparsable data"
        if (code == 502 && bodyText?.contains("oxend returned unparsable data", ignoreCase = true) == true) {
            // penalise the destination snode and retry
            pathManager.handleBadSnode(snode = ctx.targetSnode, swarmPublicKey = ctx.swarmPublicKey, forceRemove = true)
            return FailureDecision.Retry
        }

        // Destination snode not ready
        if(code == 503 && bodyText?.contains("Snode not ready", ignoreCase = true) == true){
            // penalise the destination snode and retry
            pathManager.handleBadSnode(snode = ctx.targetSnode, swarmPublicKey = ctx.swarmPublicKey)
            return FailureDecision.Retry
        }

        return FailureDecision.Fail(RuntimeException("Unhandled snode client error code: $code"))
    }
}

object SnodeClientFailureKey : ApiExecutorContext.Key<SnodeClientFailureContext>

data class SnodeClientFailureContext(
    val targetSnode: Snode,
    val swarmPublicKey: String? = null,
    val previousError: OnionError? = null // in some situations we could be coming from a retry to a previous error
)
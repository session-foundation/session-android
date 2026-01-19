package org.session.libsession.network

import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.util.findCause
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class SnodeClientErrorManager @Inject constructor(
    private val pathManager: PathManager,
    private val swarmDirectory: SwarmDirectory,
    private val snodeClock: SnodeClock,
) {

    suspend fun onFailure(code: Int?, bodyText: String?, ctx: SnodeClientFailureContext): FailureDecision? {
        // 406 is 'Clock out of sync' for a snode destination
        if (code == 406) {
            // if this is the first time we got a COS, retry, since we should have resynced the clock
            Log.w("Onion Request", "Clock out of sync (code: $code) for destination snode ${ctx.targetSnode.address} - Local Snode clock at ${snodeClock.currentTime()} - First time? ${ctx.previousErrorCode == null}")
            if (ctx.previousErrorCode == 406) {
                // if we already got a COS, and syncing the clock wasn't enough
                // we should consider the destination snode faulty. Drop from pool and swarm swarm and retry
                // handleBadSnode will handle removing the snode from the paths/pool/swarm and clean up the strikes
                // if needed
                pathManager.handleBadSnode(snode = ctx.targetSnode, swarmPublicKey = ctx.swarmPublicKey, forceRemove = true)
                return FailureDecision.Retry
            } else {
                // reset the clock
                val resync = runCatching {
                    snodeClock.resyncClock()
                }.getOrDefault(false)

                // only retry if we were able to resync the clock
                return if (resync) FailureDecision.Retry else FailureDecision.Fail
            }
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

        return null
    }
}

object SnodeClientFailureKey : ApiExecutorContext.Key<SnodeClientFailureContext>

data class SnodeClientFailureContext(
    val targetSnode: Snode,
    val swarmPublicKey: String? = null,
    val previousErrorCode: Int? = null,
)
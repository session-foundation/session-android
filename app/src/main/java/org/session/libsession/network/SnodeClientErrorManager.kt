package org.session.libsession.network

import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class SnodeClientErrorManager @Inject constructor(
    private val pathManager: PathManager,
    private val swarmDirectory: SwarmDirectory,
    private val snodeClock: SnodeClock,
) {

    suspend fun onFailure(error: OnionError, ctx: SnodeClientFailureContext): FailureDecision {
        val status = error.status
        val code = status?.code
        val bodyText = status?.bodyText

        // --------------------------------------------------------------------
        // Destination payload rules
        // --------------------------------------------------------------------
        if (error is OnionError.DestinationError) {
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
                    return if(resync) FailureDecision.Retry else FailureDecision.Fail(error)
                } else {
                    // if we already got a COS, and syncing the clock wasn't enough
                    // we should consider the destination snode faulty. Drop from swarm and retry
                    if(ctx.publicKey != null) {
                        swarmDirectory.dropSnodeFromSwarmIfNeeded(
                            snode = ctx.targetSnode,
                            publicKey = ctx.publicKey
                        )
                        return FailureDecision.Retry
                    } else { // if no public key is available, there is no swarm to heal, simply fail
                        return FailureDecision.Fail(error)
                    }
                }
            }

            // 421: snode isn't associated with pubkey anymore -> update swarm / invalidate -> retry
            if (code == 421) {
                val publicKey = ctx.publicKey
                val targetSnode = ctx.targetSnode

                val updated = if (publicKey != null) {
                    Log.w("Onion Request", "Got 421 with an associated public key. Update Swarm.")
                    swarmDirectory.updateSwarmFromResponse(
                        publicKey = publicKey,
                        body = status.body
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

            // Anything else from destination: do not penalise path; no retries
            return FailureDecision.Fail(error)
        }

        // Default: fail
        return FailureDecision.Fail(error)
    }
}

data class SnodeClientFailureContext(
    val targetSnode: Snode,
    val publicKey: String? = null,
    val previousError: OnionError? = null // in some situations we could be coming from a retry to a previous error
)
package org.thoughtcrime.securesms.api.snode

import org.session.libsession.network.SnodeClock
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.onion.PathManager
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ClockOutOfSyncException
import org.thoughtcrime.securesms.api.error.UnknownHttpStatusCodeException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class SnodeApiErrorManager @Inject constructor(
    private val pathManager: PathManager,
    private val snodeClock: SnodeClock,
) {

    /**
     * Inspect the error code coming from an [SnodeApi],
     * returning a [Throwable] for error propagating and a [FailureDecision] if an error handling
     * decision is made.
     */
    suspend fun onFailure(snode: Snode,
                          errorCode: Int,
                          bodyText: String?,
                          ctx: SnodeClientFailureContext): Pair<Throwable, FailureDecision?> {
        // 406 is 'Clock out of sync' for a snode destination
        if (errorCode == 406) {
            // if this is the first time we got a COS, retry, since we should have resynced the clock
            Log.w("Onion Request", "Clock out of sync (code: $errorCode) for destination snode ${snode.address} - Local Snode clock at ${snodeClock.currentTime()} - First time? ${ctx.previousErrorCode == null}")
            if (ctx.previousErrorCode == 406) {
                // if we already got a COS, and syncing the clock wasn't enough
                // we should consider the destination snode faulty. Drop from pool and swarm swarm and retry
                // handleBadSnode will handle removing the snode from the paths/pool/swarm and clean up the strikes
                // if needed
                pathManager.handleBadSnode(snode = snode, forceRemove = true)
                return ClockOutOfSyncException() to FailureDecision.Retry
            } else {
                // reset the clock
                val resync = runCatching {
                    snodeClock.resyncClock()
                }.getOrDefault(false)

                // only retry if we were able to resync the clock
                return ClockOutOfSyncException() to (if (resync) FailureDecision.Retry else FailureDecision.Fail)
            }
        }

        // 421: Snode not part of swarm
        if (errorCode == 421) {
            return SnodeNotPartOfSwarmException(bodyText.orEmpty(), snode) to null
        }

        // Unparseable data: 502 + "oxend returned unparsable data"
        if (errorCode == 502 && bodyText?.contains("oxend returned unparsable data", ignoreCase = true) == true) {
            // penalise the destination snode and retry
            pathManager.handleBadSnode(snode = snode, forceRemove = true)
            return RuntimeException("Unparseable data") to FailureDecision.Retry
        }

        // Destination snode not ready
        if(errorCode == 503 && bodyText?.contains("Snode not ready", ignoreCase = true) == true){
            // penalise the destination snode and retry
            pathManager.handleBadSnode(snode = snode)
            return RuntimeException("Snode not ready") to FailureDecision.Retry
        }

        return UnknownHttpStatusCodeException(errorCode, "Snode ${snode.address}", bodyText) to null
    }
}

object SnodeClientFailureKey : ApiExecutorContext.Key<SnodeClientFailureContext>

data class SnodeClientFailureContext(
    val previousErrorCode: Int? = null,
)
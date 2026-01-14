package org.session.libsession.network

import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionError
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ServerClientErrorManager @Inject constructor(
    private val snodeClock: SnodeClock,
) {

    suspend fun onFailure(error: OnionError, ctx: ServerClientFailureContext): FailureDecision {
        val status = error.status
        val code = status?.code
        val bodyText = status?.bodyText

        // --------------------------------------------------------------------
        // Destination payload rules
        // --------------------------------------------------------------------
        if (error is OnionError.DestinationError) {
            // 425 is 'Clock out of sync' for a server destination
            if (code == 425) {
                // if this is the first time we got a COS, retry, since we should have resynced the clock
                Log.w("Onion Request", "Clock out of sync (code: $code) for destination server ${ctx.url} - Local Snode clock at ${snodeClock.currentTime()} - First time? ${ctx.previousError == null}")
                if(ctx.previousError == null){
                    // reset the clock
                    val resync = runCatching {
                        snodeClock.resyncClock()
                    }.getOrDefault(false)

                    // only retry if we were able to resync the clock
                    return if(resync) FailureDecision.Retry else FailureDecision.Fail(error)
                } else {
                    // if we already got a COS, and syncing the clock wasn't enough
                    // there is nothing more to do with servers. Consider it a failed request
                    return FailureDecision.Fail(error)
                }
            }
        }

        // Default: fail
        return FailureDecision.Fail(error)
    }
}

data class ServerClientFailureContext(
    val url: String,
    val previousError: OnionError? = null // in some situations we could be coming from a retry to a previous error
)
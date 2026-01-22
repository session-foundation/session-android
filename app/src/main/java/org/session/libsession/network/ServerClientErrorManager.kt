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

    suspend fun onFailure(errorCode: Int, ctx: ServerClientFailureContext): FailureDecision? {
        // 425 is 'Clock out of sync' for a server destination
        if (errorCode == 425) {
            // if this is the first time we got a COS, retry, since we should have resynced the clock
            Log.w("Onion Request", "Clock out of sync (code: $errorCode) for destination server ${ctx.url} - Local Snode clock at ${snodeClock.currentTime()} - First time? ${ctx.previousErrorCode == null}")
            if (ctx.previousErrorCode == 425) {
                return FailureDecision.Fail
            } else {
                // reset the clock
                val resync = runCatching {
                    snodeClock.resyncClock()
                }.getOrDefault(false)

                // only retry if we were able to resync the clock
                return if(resync) FailureDecision.Retry else FailureDecision.Fail
            }
        }

        return null
    }
}

data class ServerClientFailureContext(
    val url: String,
    val previousErrorCode: Int? = null,
)
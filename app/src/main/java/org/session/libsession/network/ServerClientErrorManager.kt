package org.session.libsession.network

import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionError
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.error.ClockOutOfSyncException
import org.thoughtcrime.securesms.api.error.UnknownHttpStatusCodeException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ServerClientErrorManager @Inject constructor(
    private val snodeClock: SnodeClock,
) {

    /**
     * Handles server api error and returning a mapped [Throwable] and optionally, its decision for error
     * handling.
     *
     */
    suspend fun onFailure(errorCode: Int,
                          bodyAsText: String?,
                          serverBaseUrl: String,
                          ctx: ServerClientFailureContext): Pair<Throwable, FailureDecision?> {
        // 425 is 'Clock out of sync' for a server destination
        if (errorCode == 425) {
            // if this is the first time we got a COS, retry, since we should have resynced the clock
            Log.w("Onion Request", "Clock out of sync (code: $errorCode) for destination server ${serverBaseUrl} - Local Snode clock at ${snodeClock.currentTime()} - First time? ${ctx.previousErrorCode == null}")
            return ClockOutOfSyncException() to if (ctx.previousErrorCode == 425) {
                FailureDecision.Fail
            } else {
                // reset the clock
                val resync = runCatching {
                    snodeClock.resyncClock()
                }.getOrDefault(false)

                // only retry if we were able to resync the clock
                if(resync) FailureDecision.Retry else FailureDecision.Fail
            }
        }

        return UnknownHttpStatusCodeException(
            code = errorCode,
            origin = serverBaseUrl,
            bodyText = bodyAsText
        ) to null
    }
}

data class ServerClientFailureContext(
    val previousErrorCode: Int? = null,
)
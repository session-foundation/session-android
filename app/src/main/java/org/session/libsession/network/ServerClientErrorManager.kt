package org.session.libsession.network

import okhttp3.HttpUrl
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
class ServerClientErrorManager @Inject constructor() {

    suspend fun onFailure(error: OnionError, ctx: ServerClientFailureContext): FailureDecision {
        val status = error.status
        val code = status?.code
        val bodyText = status?.bodyText

        // --------------------------------------------------------------------
        // Destination payload rules
        // --------------------------------------------------------------------
        if (error is OnionError.DestinationError) {
            // Clock Out Of Sync
            if (error is OnionError.ClockOutOfSync)
            {
                // if this is the first time we got a COS, retry, since we should have resynced the clock
                if(ctx.previousError == null){
                    return FailureDecision.Retry

                } else {
                    // if we already got a COS, and syncing the clock wasn't enough
                    // there is nothing more to do with servers. Consider it a failed request
                    return FailureDecision.Fail(error)
                }
            }

            // Anything else from destination: do not penalise path; no retries
            return FailureDecision.Fail(error)
        }

        // Default: fail
        return FailureDecision.Fail(error)
    }
}

data class ServerClientFailureContext(
    val url: HttpUrl,
    val previousError: OnionError? = null // in some situations we could be coming from a retry to a previous error
)
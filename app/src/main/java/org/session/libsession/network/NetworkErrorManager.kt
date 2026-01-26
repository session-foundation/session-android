package org.session.libsession.network

import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.util.NetworkConnectivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkErrorManager @Inject constructor(
    private val pathManager: PathManager,
    private val snodeDirectory: SnodeDirectory,
    private val connectivity: NetworkConnectivity
) {

    suspend fun onFailure(error: OnionError, ctx: NetworkFailureContext): FailureDecision {
        //todo ONION investigate why we got stuck in a invalid cyphertext state

        // --------------------------------------------------------------------
        // 1) "Found anywhere" rules (path OR destination) - currently no custom handling here
        // as we now default to non penalising path logic
        // --------------------------------------------------------------------


        // --------------------------------------------------------------------
        // 2) Errors along the path (not destination)
        // --------------------------------------------------------------------
        when (error) {
            // we got an error building the request. Warrants retrying
            is OnionError.EncodingError -> {
                return FailureDecision.Retry
            }

            is OnionError.GuardUnreachable -> {
                // We couldn't reach the guard, yet we seem to have network connectivity:
                // punish the node and try again
                if(connectivity.networkAvailable.value) {
                    pathManager.handleBadSnode(ctx.path.first())
                    return FailureDecision.Retry
                }

                // otherwise fail
                return FailureDecision.Fail
            }

            is OnionError.IntermediateNodeUnreachable -> {
                val failedKey = error.failedPublicKey

                // Get the snode from the path (it should be there based on the error type)
                val snodeInPath = ctx.path.firstOrNull { it.publicKeySet?.ed25519Key == failedKey }

                // Fall back to pool instance only for cleanup (won’t help this request’s path)
                // If for some reason it isn't in the path, we'll still look for it in the pool
                val snodeToRemove = snodeInPath ?: snodeDirectory.getSnodeByKey(failedKey)

                // drop the bad snode, including cascading clean ups
                if (snodeToRemove != null) {
                    pathManager.handleBadSnode(snode = snodeToRemove, forceRemove = true)
                }

                // Only retry if we actually changed the path used by this request
                return if (snodeInPath != null) FailureDecision.Retry else FailureDecision.Fail
            }

            is OnionError.SnodeNotReady -> {
                // penalise the snode and retry
                val failedKey = error.failedPublicKey
                val snodeToRemove = snodeDirectory.getSnodeByKey(failedKey)
                if(snodeToRemove != null) {
                    pathManager.handleBadSnode(snodeToRemove)
                    return FailureDecision.Retry
                } else {
                    return FailureDecision.Fail
                }
            }

            is OnionError.PathTimedOut,
                 is OnionError.InvalidHopResponse -> {
                // we don't have enough information to penalise a specific snode,
                // so we penalise the whole path and try again
                pathManager.handleBadPath(ctx.path)
                return FailureDecision.Retry
            }

            is OnionError.DestinationUnreachable -> {
                if (error.destination is OnionDestination.SnodeDestination) {
                    pathManager.handleBadSnode(error.destination.snode)
                }

                return FailureDecision.Retry
            }
            is OnionError.InvalidResponse,
            is OnionError.PathError,
            is OnionError.Unknown -> {
                return FailureDecision.Fail
            }
        }

        // --------------------------------------------------------------------
        // 3) Destination payload rules - currently this doesn't handle
        //    DestinatioErrors directly. The clients' error manager do.
        // --------------------------------------------------------------------
    }
}

data class NetworkFailureContext(
    val path: Path,
    val destination: OnionDestination,
    val targetSnode: Snode? = null,
    val publicKey: String? = null,
    val previousError: OnionError? = null // in some situations we could be coming from a retry to a previous error
)

object NetworkFailureKey : ApiExecutorContext.Key<NetworkFailureContext>
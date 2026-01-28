package org.thoughtcrime.securesms.api.onion

import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.PathManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnionSessionApiErrorManager @Inject constructor(
    private val pathManager: PathManager,
    private val connectivity: NetworkConnectivity
) {

    suspend fun onFailure(
        error: OnionError,
        path: Path,
        shouldPunishPath: Boolean,
    ): FailureDecision {
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
                    pathManager.handleBadSnode(error.guard)
                    return FailureDecision.Retry
                }

                // otherwise fail
                return FailureDecision.Fail
            }

            is OnionError.IntermediateNodeUnreachable -> {
                // drop the bad snode, including cascading clean ups
                if (error.offendingSnode != null) {
                    pathManager.handleBadSnode(snode = error.offendingSnode, forceRemove = true)
                }

                // Only retry if we actually changed the path used by this request
                return if (error.offendingSnode != null) FailureDecision.Retry else FailureDecision.Fail
            }

            is OnionError.SnodeNotReady -> {
                // penalise the snode and retry
                return if(error.offendingSnode != null) {
                    pathManager.handleBadSnode(error.offendingSnode)
                    FailureDecision.Retry
                } else {
                    FailureDecision.Fail
                }
            }

            is OnionError.PathTimedOut, is OnionError.InvalidHopResponse -> {
                return if (shouldPunishPath) {
                    // we don't have enough information to penalise a specific snode,
                    // so we penalise the whole path and try again
                    pathManager.handleBadPath(path)
                    FailureDecision.Retry
                } else {
                    FailureDecision.Fail
                }
            }

            is OnionError.DestinationUnreachable -> {
                if (error.destination is OnionDestination.SnodeDestination) {
                    pathManager.handleBadSnode(error.destination.snode, forceRemove = true)
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
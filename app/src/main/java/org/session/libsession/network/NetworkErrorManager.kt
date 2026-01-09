package org.session.libsession.network

import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
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
        val status = error.status
        val code = status?.code
        val bodyText = status?.bodyText

        //todo ONION investigate why we got stuck in a invalid cyphertext state
        //todo ONION how can we deal with errors sent from the destination, but not within the 200 > encrypted package? Currently they will become PathError that will wrongly penalise the path

        // --------------------------------------------------------------------
        // 1) "Found anywhere" rules (path OR destination)
        // --------------------------------------------------------------------

        // 400, 403, 404: do not penalise path or snode; No retries
        if (code == 400 || code == 403 || code == 404) {
            return FailureDecision.Fail(error)
        }

        // --------------------------------------------------------------------
        // 2) Errors along the path (not destination)
        // --------------------------------------------------------------------
        when (error) {
            is OnionError.IntermediateNodeFailed -> {
                // Drop snode from pool, rebuild paths without it, penalise path, retry
                val failedKey = error.failedPublicKey
                if (failedKey != null) {
                    snodeDirectory.dropSnodeFromPool(failedKey)
                }

                // If we can map the failed key to an actual snode in this path, prefer handleBadSnode
                val bad = failedKey?.let { pk ->
                    ctx.path.firstOrNull { it.publicKeySet?.ed25519Key == pk }
                }

                //todo ONION we are actually supposed to handle the bad snode AND also penalise the path in this case, even if the node was swapped out
                //todo ONION and in this case handleBadSnode should not strike but definitely remove the snode
                if (bad != null) pathManager.handleBadSnode(bad)
                else pathManager.handleBadPath(ctx.path)

                return FailureDecision.Retry
            }

            is OnionError.DestinationUnreachable -> {
                //todo ONION implement this properly

                return FailureDecision.Fail(error)
            }

            is OnionError.PathError -> {
                // "Anything else along the path": penalise path; no retries (caller decides)
                pathManager.handleBadPath(ctx.path)
                return FailureDecision.Fail(error)
            }

            is OnionError.GuardUnreachable -> {
                // We couldn't reach the guard, yet we seem to have network connectivity:
                // punish the node and try again
                if(connectivity.networkAvailable.value) {
                    pathManager.handleBadSnode(ctx.path.first())
                    return FailureDecision.Retry
                }

                // otherwise fail
                return FailureDecision.Fail(error)
            }

            is OnionError.InvalidResponse -> {
                // penalise path; retry
                pathManager.handleBadPath(ctx.path)
                return FailureDecision.Retry
            }

            is OnionError.Unknown -> {
                return FailureDecision.Retry
            }

            is OnionError.DestinationError -> {
                FailureDecision.Fail(error)
            }
        }

        // --------------------------------------------------------------------
        // 3) Destination payload rules - currently this doesn't handle
        //    DestinatioErrors directly. The clients' error manager do.
        // --------------------------------------------------------------------

        // Default: fail
        return FailureDecision.Fail(error)
    }
}

data class NetworkFailureContext(
    val path: Path,
    val destination: OnionDestination,
    val targetSnode: Snode? = null,
    val publicKey: String? = null,
    val previousError: OnionError? = null // in some situations we could be coming from a retry to a previous error
)
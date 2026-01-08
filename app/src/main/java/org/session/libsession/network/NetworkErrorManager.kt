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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkErrorManager @Inject constructor(
    private val pathManager: PathManager,
    private val snodeDirectory: SnodeDirectory,
    private val swarmDirectory: SwarmDirectory,
    private val snodeClock: SnodeClock,
) {

    suspend fun onFailure(error: OnionError, ctx: NetworkFailureContext): FailureDecision {
        val status = error.status
        val code = status?.code
        val bodyText = status?.bodyText

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

                if (bad != null) pathManager.handleBadSnode(bad)
                else pathManager.handleBadPath(ctx.path)

                return FailureDecision.Retry
            }

            is OnionError.PathError -> {
                // "Anything else along the path": penalise path; no retries (caller decides)
                pathManager.handleBadPath(ctx.path)
                return FailureDecision.Fail(error)
            }

            is OnionError.GuardUnreachable -> {
                // penalise path; retry
                //todo ONION if our connectivity manager tells us we have network, punish node (maybe after a couple of strikes?) and try again - otherwise fail
                pathManager.handleBadPath(ctx.path)
                return FailureDecision.Retry
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
package org.session.libsession.network

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

private const val REQUIRE_BLINDING_MESSAGE =
    "Invalid authentication: this server requires the use of blinded ids"

@Singleton
class OnionErrorManager @Inject constructor(
    private val pathManager: PathManager,
    private val snodeDirectory: SnodeDirectory,
    private val swarmDirectory: SwarmDirectory,
    private val snodeClock: SnodeClock,
) {

    suspend fun onFailure(error: OnionError, ctx: OnionFailureContext): FailureDecision {
        val status = error.status
        val code = status?.code
        val bodyText = status?.bodyText

        // --------------------------------------------------------------------
        // 1) "Found anywhere" rules (path OR destination)
        // --------------------------------------------------------------------

        // 400, 403, 404: do not penalise path or snode; No retries
        if (code == 400 || code == 403 || code == 404) {
            //todo ONION need to move the REQUIRE_BLINDING_MESSAGE logic out of here, it should be handled at the calling site, in this case the community poller, to then call /capabilities once
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
                //todo ONION not sure yet whether we should punish the path here, or even if we should retry as it is likely a "no connection" issue
                pathManager.handleBadPath(ctx.path)
                return FailureDecision.Retry
            }

            // InvalidResponse / Unknown: treat as path failure (penalise path; retry)
            is OnionError.InvalidResponse,
            is OnionError.Unknown -> {
                //todo ONION also not sure whether to penalise path and retry here...
                pathManager.handleBadPath(ctx.path)
                return FailureDecision.Retry
            }

            else -> Unit
        }

        // --------------------------------------------------------------------
        // 3) Destination payload rules
        // --------------------------------------------------------------------
        if (error is OnionError.DestinationError) {
            // 406/425: clock out of sync (COS)
            // 406 is COS only for a snode destination
            // 425 is COS only for a server destination
            if ((code == 406 && ctx.destination is OnionDestination.SnodeDestination)
                || (code == 425 && ctx.destination is OnionDestination.ServerDestination))
            {
                Log.w("Onion Request", "Clock out of sync (code: $code) for destination ${ctx.targetSnode?.address} at node: ${error.snode?.address} - Local Snode clock at ${snodeClock.currentTime()}")
                // Attempt to reset the clock.
                // The retry logic for COS shouldn't be handled here, but at a higher level
                // since the clock will be reset, meaning request that need a timestamp will need
                // to be recreated, so the responsibility should like on a layer further up
                // for example in the SnodeErrorManager or ServerErrorManager
                runCatching {
                    snodeClock.resyncClock()
                    //todo ONION Add retry logic in the snode and server error managers. If we still get an out of sync error, we should penalise the snode, and try again with another
                }.getOrDefault(false)

                return FailureDecision.Fail(OnionError.ClockOutOfSync(error.destination, error.status))
            }

            // 421: snode isn't associated with pubkey anymore -> update swarm / invalidate -> retry
            //todo ONION this should be moved to SnodeErrorManager
            if (code == 421) {
                val publicKey = ctx.publicKey
                val targetSnode = ctx.targetSnode

                val updated = if (publicKey != null) {
                    swarmDirectory.updateSwarmFromResponse(
                        publicKey = publicKey,
                        body = status.body
                    )
                } else {
                    Log.w("Onion Request", "Got 421 without an associated public key.")
                    false
                }

                if (!updated && publicKey != null && targetSnode != null) {
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

data class OnionFailureContext(
    val path: Path,
    val destination: OnionDestination,
    val targetSnode: Snode? = null,
    val publicKey: String? = null,
    val previousError: OnionError? = null // in some situations we could be coming from a retry to a previous error
)

sealed class FailureDecision {
    data object Retry : FailureDecision()
    data class Fail(val throwable: Throwable) : FailureDecision()
}
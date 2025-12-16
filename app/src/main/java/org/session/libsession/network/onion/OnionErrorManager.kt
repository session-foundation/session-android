package org.session.libsession.network.onion

import org.session.libsession.network.SnodeClock
import org.session.libsession.network.model.ErrorOrigin
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.Path
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

        // 406/425: clock out of sync
        if (code == 406 || code == 425) {
            // Do not penalise path or snode. Reset the clock. Retry if reset succeeded.
            val resetOk = runCatching {
                //snodeClock.resync()
                //todo ONION Can we do some clock reset here?
                false
            }.getOrDefault(false)
            return if (resetOk) FailureDecision.Retry else FailureDecision.Fail(error)
        }

        // 400 (except blinding), 403, 404: do not penalise path or snode; retry
        if (code == 400 || code == 403 || code == 404) {
            // carve-out: destination 400 with blinding message is caller-handled
            if (code == 400 && bodyText?.contains(REQUIRE_BLINDING_MESSAGE) == true) {
                return FailureDecision.Fail(error)
            }
            return FailureDecision.Retry
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
                // Networky: penalise path; retry
                pathManager.handleBadPath(ctx.path)
                return FailureDecision.Retry
            }

            // InvalidResponse / Unknown: treat as path failure (penalise path; retry)
            is OnionError.InvalidResponse,
            is OnionError.Unknown -> {
                pathManager.handleBadPath(ctx.path)
                return FailureDecision.Retry
            }

            else -> Unit
        }

        // --------------------------------------------------------------------
        // 3) Destination payload rules
        // --------------------------------------------------------------------
        if (error is OnionError.DestinationError) {
            // 421: snode isn't associated with pubkey anymore -> update swarm / invalidate -> retry
            if (code == 421) {
                val publicKey = ctx.publicKey
                val targetSnode = ctx.targetSnode

                val updated = if (publicKey != null) {
                    swarmDirectory.tryUpdateSwarmFrom421(
                        publicKey = publicKey,
                        body = status.body
                    )
                } else {
                    Log.w("Onion", "Got 421 without an associated public key.")
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
    val publicKey: String? = null
)

sealed class FailureDecision {
    data object Retry : FailureDecision()
    data class Fail(val throwable: Throwable) : FailureDecision()
}
package org.session.libsession.network

import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.OnionTransport
import org.session.libsession.network.onion.Version
import org.session.libsession.network.onion.PathManager
import org.session.libsignal.utilities.Snode
import org.session.libsignal.utilities.Log

/**
 * High-level façade over onion routing:
 *
 *  - asks PathManager for a path
 *  - uses OnionTransport to send over that path
 *  - maps OnionError -> path/node repair via PathManager
 *  - decides whether to retry once with a new path
 */
class SessionNetwork(
    private val pathManager: PathManager,
    private val transport: OnionTransport,
) {

    /**
     * Main entry point for “send an onion request”.
     *
     * - destination: Snode or Server (file server, open group, etc.)
     * - payload: the already-built request body to wrap in an onion
     * - version: V2/V3/V4 onion protocol
     */
    suspend fun sendOnionRequest(
        destination: OnionDestination,
        payload: ByteArray,
        version: Version = Version.V4,
    ): Result<OnionResponse> {
        // If the destination is a specific snode, try not to route *through* it
        val snodeToExclude: Snode? = when (destination) {
            is OnionDestination.SnodeDestination  -> destination.snode
            is OnionDestination.ServerDestination -> null
        }

        // 1. Pick a path
        val initialPath = try {
            pathManager.getPath(exclude = snodeToExclude)
        } catch (t: Throwable) {
            return Result.failure(t)
        }

        // 2. First attempt
        val first = transport.send(
            path = initialPath,
            destination = destination,
            payload = payload,
            version = version
        )

        if (first.isSuccess) {
            return first
        }

        val error = first.exceptionOrNull()
        if (error !is OnionError) {
            // Some unexpected exception coming out of the transport.
            Log.w("SessionNetwork", "Non-OnionError failure: $error")
            return Result.failure(error ?: IllegalStateException("Unknown failure"))
        }

        // 3. Let PathManager react (drop path / snode)
        handleOnionError(initialPath, error)

        // 4. Decide whether to retry
        if (!shouldRetry(error)) {
            return Result.failure(error)
        }

        // 5. Retry once with a new path
        val retryPath = try {
            pathManager.getPath(exclude = snodeToExclude)
        } catch (t: Throwable) {
            // Couldn't even get a new path; keep original onion error
            return Result.failure(error)
        }

        val retry = transport.send(
            path = retryPath,
            destination = destination,
            payload = payload,
            version = version
        )

        // If second attempt fails with an OnionError, update paths again
        val retryErr = retry.exceptionOrNull()
        if (retryErr is OnionError) {
            handleOnionError(retryPath, retryErr)
        }

        return retry
    }

    /**
     * Map a specific OnionError to PathManager operations (node/path surgery).
     */
    private fun handleOnionError(path: Path, error: OnionError) {
        when (error) {
            is OnionError.GuardConnectionFailed -> {
                // Guard is the first node in the path.
                Log.w("SessionNetwork", "Guard connection failed for ${error.guard}, dropping path")
                pathManager.handleBadPath(path)
            }

            is OnionError.GuardProtocolError -> {
                Log.w(
                    "SessionNetwork",
                    "Guard protocol error code=${error.code}, dropping path"
                )
                pathManager.handleBadPath(path)
            }

            is OnionError.IntermediateNodeFailed -> {
                val failedKey = error.failedPublicKey
                if (failedKey != null) {
                    val badNode = findNodeByEd25519(path, failedKey)
                    if (badNode != null) {
                        Log.w("SessionNetwork", "Intermediate node failed: $badNode, repairing path")
                        pathManager.handleBadSnode(badNode)
                    } else {
                        Log.w(
                            "SessionNetwork",
                            "Intermediate node failed; key not found in path. Dropping path."
                        )
                        pathManager.handleBadPath(path)
                    }
                } else {
                    Log.w("SessionNetwork", "Intermediate node failed (no failed key); dropping path")
                    pathManager.handleBadPath(path)
                }
            }

            is OnionError.DestinationUnreachable -> {
                // Exit node is usually last in the path
                val exit = error.exitNode ?: path.lastOrNull()
                if (exit != null && path.contains(exit)) {
                    Log.w("SessionNetwork", "Destination unreachable; marking exit node $exit as bad")
                    pathManager.handleBadSnode(exit)
                } else {
                    Log.w("SessionNetwork", "Destination unreachable; dropping entire path")
                    pathManager.handleBadPath(path)
                }
            }

            is OnionError.DestinationError -> {
                // Pure app-level error (404, 401, etc.): path is fine.
                Log.i("SessionNetwork", "Destination error ${error.code}, not penalising path")
            }

            is OnionError.ClockOutOfSync -> {
                // Network is “working” but user must fix their device clock.
                Log.w("SessionNetwork", "Clock out of sync: code=${error.code}")
            }

            is OnionError.InvalidResponse -> {
                Log.w("SessionNetwork", "Invalid onion response, dropping path")
                pathManager.handleBadPath(path)
            }

            is OnionError.Unknown -> {
                Log.w("SessionNetwork", "Unknown onion error, dropping path: ${error.underlying}")
                pathManager.handleBadPath(path)
            }
        }
    }

    /**
     * Policy: when does it make sense to try again with a new path?
     */
    private fun shouldRetry(error: OnionError): Boolean =
        when (error) {
            is OnionError.GuardConnectionFailed  -> true   // try another guard/path
            is OnionError.GuardProtocolError     -> true   // different guard may succeed
            is OnionError.IntermediateNodeFailed -> true   // path surgery then retry
            is OnionError.DestinationUnreachable -> true   // exit/node connectivity
            is OnionError.DestinationError       -> false  // app-level; retrying won’t fix 404/401
            is OnionError.ClockOutOfSync         -> false  // must fix clock
            is OnionError.InvalidResponse        -> true   // treat as corrupt path
            is OnionError.Unknown                -> true   // conservative
        }

    /**
     * Find a node in the path by its ed25519 public key.
     */
    private fun findNodeByEd25519(path: Path, ed25519: String): Snode? =
        path.firstOrNull { it.publicKeySet?.ed25519Key == ed25519 }
}

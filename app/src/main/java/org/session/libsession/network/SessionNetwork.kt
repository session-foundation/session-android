package org.session.libsession.network

import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.OnionTransport
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.onion.Version
import org.session.libsession.network.utilities.retryWithBackOff
import org.session.libsignal.utilities.Snode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level onion request manager.
 *
 * Responsibilities:
 * - Prepare payloads
 * - Choose onion paths
 * - Retry loop + (light) retry timing/backoff
 * - Delegate all “what do we do with this OnionError?” decisions to OnionErrorManager
 *
 * Not responsible for:
 * - Onion crypto construction or transport I/O (OnionTransport)
 * - Policy / healing logic (OnionErrorManager)
 */
@Singleton
class SessionNetwork @Inject constructor(
    private val pathManager: PathManager,
    private val transport: OnionTransport,
    private val errorManager: NetworkErrorManager,
) {

    internal suspend fun sendWithRetry(
        destination: OnionDestination,
        payload: ByteArray,
        version: Version,
        snodeToExclude: Snode?,
        targetSnode: Snode?,
        publicKey: String?
    ): OnionResponse {
        var lastPath: Path? = null

        return retryWithBackOff(
            operationName = "OnionRequest",
            classifier = { error, previousError ->
                // lastPath should always be set before transport.send() is called
                val path = requireNotNull(lastPath) { "Path not set for onion retry classifier" }

                errorManager.onFailure(
                    error = error,
                    ctx = NetworkFailureContext(
                        path = path,
                        destination = destination,
                        targetSnode = targetSnode,
                        publicKey = publicKey,
                        previousError = previousError
                    )
                )
            }
        ) {
            val path = pathManager.getPath(exclude = snodeToExclude)
            lastPath = path

            transport.send(
                path = path,
                destination = destination,
                payload = payload,
                version = version
            )
        }
    }
}

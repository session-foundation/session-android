package org.session.libsession.network

import kotlinx.coroutines.delay
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.OnionTransport
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.onion.Version
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

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
    private val errorManager: OnionErrorManager,
) {

    private val maxAttempts: Int = 2
    private val baseRetryDelayMs: Long = 250L
    private val maxRetryDelayMs: Long = 2_000L


    internal suspend fun sendWithRetry(
        destination: OnionDestination,
        payload: ByteArray,
        version: Version,
        snodeToExclude: Snode?,
        targetSnode: Snode?,
        publicKey: String?
    ): OnionResponse {
        var lastError: OnionError? = null

        for (attempt in 1..maxAttempts) {
            val path: Path = pathManager.getPath(exclude = snodeToExclude)
            //Log.i("Onion Request", "Sending onion request to $destination - attempt $attempt/$maxAttempts")

            try {
                val result = transport.send(
                    path = path,
                    destination = destination,
                    payload = payload,
                    version = version
                )

                return result
            } catch (e: Throwable) {
                val onionError = e as? OnionError ?: OnionError.Unknown(e)

                Log.w("Onion Request", "Onion error on attempt $attempt/$maxAttempts: $onionError")

                // Delegate all handling + retry decision
                val decision = errorManager.onFailure(
                    error = onionError,
                    ctx = OnionFailureContext(
                        path = path,
                        destination = destination,
                        targetSnode = targetSnode,
                        publicKey = publicKey,
                        previousError = lastError
                    )
                )

                lastError = onionError

                when (decision) {
                    is FailureDecision.Fail -> throw decision.throwable
                    FailureDecision.Retry -> {
                        if (attempt >= maxAttempts) break
                        delay(computeBackoffDelayMs(attempt))
                        continue
                    }
                }
            }
        }

        throw lastError ?: IllegalStateException("Unknown onion error")
    }

    private fun computeBackoffDelayMs(attempt: Int): Long {
        // Exponential-ish: base * 2^(attempt-1), with jitter, capped
        val exp = baseRetryDelayMs * (1L shl (attempt - 1).coerceAtMost(5))
        val capped = exp.coerceAtMost(maxRetryDelayMs)
        val jitter = Random.nextLong(0, capped / 3 + 1)
        return capped + jitter
    }
}

package org.session.libsession.network.utilities

import kotlinx.coroutines.delay
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.model.OnionError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

// preserving suspend context and avoiding object allocation.
/**
 * Generic retry loop that delegates the decision logic to a classifier.
 *
 * @param classifier A function that takes the current error and the previous error (if any)
 * and returns a FailureDecision (Retry or Fail).
 */
suspend inline fun <T> retryWithBackOff(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 250L,
    maxDelayMs: Long = 2000L,
    operationName: String = "Operation",
    crossinline classifier: suspend (error: OnionError, previousError: OnionError?) -> FailureDecision,
    crossinline block: suspend (attempt: Int) -> T
): T {
    var previousError: OnionError? = null

    for (attempt in 1..maxAttempts) {
        try {
            return block(attempt)
        } catch (currentError: Throwable) {
            if (currentError is CancellationException) throw currentError

            val onionError = currentError as? OnionError ?: OnionError.Unknown(currentError)

            val decision = classifier(onionError, previousError)

            previousError = onionError

            when (decision) {
                is FailureDecision.Fail -> {
                    throw decision.throwable
                }
                is FailureDecision.Retry -> {
                    //Log.w("NetworkRetry", "$operationName failed (attempt $attempt/$maxAttempts): ${currentError.message}")

                    if (attempt < maxAttempts) {
                        // Calculate Backoff
                        val exp = baseDelayMs * (1L shl (attempt - 1).coerceAtMost(5))
                        val capped = exp.coerceAtMost(maxDelayMs)
                        val jitter = Random.nextLong(0, capped / 3 + 1)
                        delay(capped + jitter)
                        continue
                    }
                }
            }
        }
    }
    throw previousError ?: IllegalStateException("$operationName failed with unknown error")
}
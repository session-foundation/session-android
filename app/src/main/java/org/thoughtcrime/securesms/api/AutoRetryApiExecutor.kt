package org.thoughtcrime.securesms.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.session.libsession.network.model.FailureDecision
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.util.findCause

/**
 * An [ApiExecutor] that automatically retries a request that fails with a [ErrorWithFailureDecision]
 * with a [FailureDecision.Retry], up to 3 times, with exponential backoff.
 *
 * **Note**:, this executor should normally be at the outermost layer of executors, so that it can
 * retry the entire request.
 */
class AutoRetryApiExecutor<Req, Res>(
    private val actualExecutor: ApiExecutor<Req, Res>,
) : ApiExecutor<Req, Res> {
    override suspend fun send(ctx: ApiExecutorContext, req: Req): Res {
        val initStack = Throwable().stackTrace

        var numRetried = 0
        while (true) {
            try {
                return actualExecutor.send(ctx, req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e.findCause<ErrorWithFailureDecision>()?.failureDecision == FailureDecision.Retry &&
                    ctx.get(DisableRetryKey) == null &&
                    numRetried <= 3) {
                    numRetried += 1
                    Log.e(TAG, "Retrying $req $numRetried times due to error", e)
                    delay(numRetried * 2000L)
                } else {
                    // If we know the error is ErrorWithFailureDecision, we can
                    // safely modify its stacktrace as we know that exception contains
                    // a cause where it can pinpoint to the direct trace of the error.
                    // Otherwise, we'll create a new exception to modify the stacktrace, so to
                    // preserve the original error's stacktrace which may contain important
                    // information about the error.

                    val errorToUpdateStack = e.takeIf { it is ErrorWithFailureDecision } ?: RuntimeException(e)
                    errorToUpdateStack.stackTrace = initStack
                    throw errorToUpdateStack
                }
            }
        }
    }

    /**
     * A key that can be added to the [ApiExecutorContext] to disable automatic retries.
     */
    object DisableRetryKey : ApiExecutorContext.Key<Unit>

    companion object {
        private const val TAG = "AutoRetryApiExecutor"
    }
}
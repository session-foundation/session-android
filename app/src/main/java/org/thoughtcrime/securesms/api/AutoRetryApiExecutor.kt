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
                } else if (e is ErrorWithFailureDecision) {
                    // If we know the error is ErrorWithFailureDecision, we can
                    // safely modify the stacktrace as we know that exception contains
                    // a cause where it can pinpoint to the direct trace of the error.
                    throw e.also { it.stackTrace = initStack }
                } else {
                    // If we aren't sure about the error type, we shouldn't modify the stacktrace
                    // as it may lose important information about the error. In this case,
                    // we'll just create a new exception with the original error as the cause,
                    // so that we can preserve the original stacktrace.
                    throw RuntimeException(e).also { it.stackTrace = initStack }
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
package org.thoughtcrime.securesms.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.session.libsession.network.model.FailureDecision
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.util.findCause

class AutoRetryApiExecutor<Req, Res>(
    private val actualExecutor: ApiExecutor<Req, Res>,
) : ApiExecutor<Req, Res> {
    override suspend fun send(ctx: ApiExecutorContext, req: Req): Res {
        var numRetried = 0
        while (true) {
            try {
                return actualExecutor.send(ctx, req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e.findCause<NonRetryableException>() != null ||
                    e.findCause<ErrorWithFailureDecision>()?.failureDecision != FailureDecision.Retry ||
                    numRetried == 2) {
                    throw e
                } else {
                    numRetried += 1
                    Log.e(TAG, "Retrying $req $numRetried times due to error", e)
                    delay(numRetried * 2000L)
                }
            }
        }
    }


    companion object {
        private const val TAG = "AutoRetryApiExecutor"
    }
}
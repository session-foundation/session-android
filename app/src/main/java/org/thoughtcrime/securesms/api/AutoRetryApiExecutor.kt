package org.thoughtcrime.securesms.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.session.libsession.network.model.FailureDecision
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.util.findCause

class AutoRetryApiExecutor<Dest, Req, Res> @AssistedInject constructor(
    @Assisted private val actualExecutor: ApiExecutor<Dest, Req, Res>,
) : ApiExecutor<Dest, Req, Res> {
    override suspend fun send(ctx: ApiExecutorContext, dest: Dest, req: Req): Res {
        var numRetried = 0
        while (true) {
            try {
                return actualExecutor.send(ctx, dest, req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e.findCause<NonRetryableException>() != null ||
                    e.findCause<ErrorWithFailureDecision>()?.failureDecision == FailureDecision.Fail ||
                    numRetried == 2) {
                    throw e
                } else {
                    numRetried += 1
                    Log.e(TAG, "Retrying ${actualExecutor.javaClass.simpleName} $numRetried times due to error", e)
                    delay(numRetried * 2000L)
                }
            }
        }
    }

    @AssistedFactory
    interface Factory<Dest, Req, Res> {
        fun create(actualExecutor: ApiExecutor<Dest, Req, Res>): AutoRetryApiExecutor<Dest, Req, Res>
    }

    companion object {
        private const val TAG = "AutoRetryApiExecutor"
    }
}
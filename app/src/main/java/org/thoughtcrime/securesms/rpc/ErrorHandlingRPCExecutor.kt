package org.thoughtcrime.securesms.rpc

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.rpc.error.ClockOutOfSyncException
import org.thoughtcrime.securesms.rpc.error.PathPenalisingException
import org.thoughtcrime.securesms.rpc.error.SnodePenalisingException
import org.thoughtcrime.securesms.util.findCause
import javax.inject.Provider

class ErrorHandlingRPCExecutor<Dest, Req, Res> @AssistedInject constructor(
    @Assisted private val actualExecutor: RPCExecutor<Dest, Req, Res>,
    private val snodeClock: SnodeClock,
    private val pathManager: Provider<PathManager>,
    private val snodeDirectory: Provider<SnodeDirectory>,
): RPCExecutor<Dest, Req, Res> {
    override suspend fun send(dest: Dest, req: Req): Res {
        var numRetried = 0
        while (true) {
            try {
                return actualExecutor.send(dest, req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
//                if (e.findCause<ClockOutOfSyncException>() != null) {
//                    snodeClock.resyncClock()
//                }
//
//                e.findCause<SnodePenalisingException>()?.let { err ->
//                    val snode = err.offendingSnode
//                        ?: snodeDirectory.get().getSnodeByKey(err.offendingSnodeED25519PubKey)
//
//                    if (snode != null) {
//                        pathManager.get().handleBadSnode(snode, forceRemove = )
//                    }
//
//                    networkClientErrorHandler.handleError(err)
//                }
//
//
//                e.findCause<PathPenalisingException>()?.let { err ->
//                    pathManager.get().handleBadPath(err.offendingPath)
//                }

                // ExceptionWithDecision(

                class ExceptionWithDecision(
                    val original: Throwable,
                    val failureDecision: FailureDecision
                ) : RuntimeException()




                if (e.findCause<NonRetryableException>() != null || numRetried == 2) {
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
    interface Factory {
        fun <Dest, Req, Res> create(actualExecutor: RPCExecutor<Dest, Req, Res>): ErrorHandlingRPCExecutor<Dest, Req, Res>
    }

    companion object {
        private const val TAG = "AutoRetryRPCExecutor"
    }
}
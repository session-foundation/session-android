package org.session.libsession.messaging.sending_receiving.pollers

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.NetworkConnectivity
import kotlin.time.Clock
import kotlin.time.Instant

abstract class BasePoller<T>(
    private val networkConnectivity: NetworkConnectivity,
    scope: CoroutineScope,
) {
    protected val logTag: String = this::class.java.simpleName
    private val pollMutex = Mutex()

    private val mutablePollState = MutableStateFlow<PollState<T>>(PollState.Idle)

    val pollState: StateFlow<PollState<T>> get() = mutablePollState

    init {
        scope.launch {
            val processLifecycleState = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            var numConsecutiveFailures = 0

            while (true) {
                // Wait until the app is in the foreground and we have network connectivity
                combine(
                    processLifecycleState.filter { it.isAtLeast(Lifecycle.State.RESUMED) },
                    networkConnectivity.networkAvailable.filter { it },
                    transform = { _, _ -> }
                ).first()

                try {
                    pollOnce("routine")
                    numConsecutiveFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    numConsecutiveFailures += 1
                }

                val nextPollSeconds = nextPollDelaySeconds(numConsecutiveFailures)
                Log.d(logTag, "Next poll in ${nextPollSeconds}s")
                delay(nextPollSeconds * 1000L)
            }
        }
    }

    protected open val successfulPollIntervalSeconds: Int get() = 2
    protected open val maxRetryIntervalSeconds: Int get() = 10

    /**
     * Returns the delay until the next poll should be performed.
     *
     * @param numConsecutiveFailures The number of consecutive polling failures that have occurred.
     *  0 indicates the last poll was successful.
     */
    private fun nextPollDelaySeconds(
        numConsecutiveFailures: Int,
    ): Int {
        return successfulPollIntervalSeconds +
                ((numConsecutiveFailures - 1).coerceAtLeast(0) * successfulPollIntervalSeconds)
                    .coerceAtMost(maxRetryIntervalSeconds)
    }

    protected abstract suspend fun doPollOnce(isFirstPollSinceApStarted: Boolean): T

    private suspend fun pollOnce(reason: String): T {
        pollMutex.withLock {
            val lastState = mutablePollState.value
            mutablePollState.value = PollState.Polling(reason, lastPolledResult = lastState.lastPolledResult)
            Log.d(logTag, "Start $reason polling")
            val result = runCatching {
                doPollOnce(isFirstPollSinceApStarted = lastState is PollState.Idle)
            }

            if (result.isSuccess) {
                Log.d(logTag, "$reason polling succeeded")
            } else if (result.exceptionOrNull() !is CancellationException) {
                Log.e(logTag, "$reason polling failed", result.exceptionOrNull())
            }

            mutablePollState.value = PollState.Polled(
                at = Clock.System.now(),
                result = result,
            )

            return result.getOrThrow()
        }
    }

    suspend fun manualPollOnce(): T {
        return pollOnce("manual")
    }


    sealed interface PollState<out T> {
        val lastPolledResult: Result<T>?

        object Idle : PollState<Nothing> {
            override val lastPolledResult: Result<Nothing>?
                get() = null
        }

        data class Polled<T>(
            val at: Instant,
            val result: Result<T>,
        ): PollState<T> {
            override val lastPolledResult: Result<T>
                get() = result
        }

        data class Polling<T>(
            val reason: String,
            override val lastPolledResult: Result<T>?,
        ) : PollState<T>
    }
}
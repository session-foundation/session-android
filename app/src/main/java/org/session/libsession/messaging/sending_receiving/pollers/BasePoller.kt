package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
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
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Base class for pollers that perform periodic polling operations. These poller will:
 *
 * 1. Run periodically when the app is in the foreground and there is network.
 * 2. Adjust the polling interval based on success/failure of previous polls.
 * 3. Expose the current polling state via [pollState]
 * 4. Allow manual polling via [manualPollOnce]
 *
 * @param T The type of the result returned by the single polling.
 */
abstract class BasePoller<T>(
    private val networkConnectivity: NetworkConnectivity,
    appVisibilityManager: AppVisibilityManager,
    private val scope: CoroutineScope,
) {
    protected val logTag: String = this::class.java.simpleName
    private val pollMutex = Mutex()

    private val mutablePollState = MutableStateFlow<PollState<T>>(PollState.Idle)

    /**
     * The current state of the poller.
     */
    val pollState: StateFlow<PollState<T>> get() = mutablePollState

    init {
        scope.launch {
            var numConsecutiveFailures = 0

            while (true) {
                // Wait until the app is in the foreground and we have network connectivity
                combine(
                    appVisibilityManager.isAppVisible.filter { visible ->
                        if (visible) {
                            true
                        } else {
                            Log.d(logTag, "Polling paused - app in background")
                            false
                        }
                    },
                    networkConnectivity.networkAvailable.filter { hasNetwork ->
                        if (hasNetwork) {
                            true
                        } else {
                            Log.d(logTag, "Polling paused - no network connectivity")
                            false
                        }
                    },
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
        val delay = successfulPollIntervalSeconds * (numConsecutiveFailures + 1)
        return delay.coerceAtMost(maxRetryIntervalSeconds)
    }

    /**
     * Performs a single polling operation. A failed poll should throw an exception.
     *
     * @param isFirstPollSinceApoStarted True if this is the first poll since the app started.
     * @return The result of the polling operation.
     */
    protected abstract suspend fun doPollOnce(isFirstPollSinceApoStarted: Boolean): T

    private suspend fun pollOnce(reason: String): T {
        pollMutex.withLock {
            val lastState = mutablePollState.value
            mutablePollState.value =
                PollState.Polling(reason, lastPolledResult = lastState.lastPolledResult)
            Log.d(logTag, "Start $reason polling")
            val result = runCatching {
                doPollOnce(isFirstPollSinceApoStarted = lastState is PollState.Idle)
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

    /**
     * Manually triggers a single polling operation.
     *
     * Note:
     * * If a polling operation is already in progress, this will wait for it to complete first.
     * * This method does not check for app foreground/background state or network connectivity.
     * * This method will throw if the polling operation fails.
     */
    suspend fun manualPollOnce(): T {
        val resultChannel = Channel<Result<T>>()

        scope.launch {
            resultChannel.trySend(runCatching {
                pollOnce("manual")
            })
        }

        return resultChannel.receive().getOrThrow()
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
        ) : PollState<T> {
            override val lastPolledResult: Result<T>
                get() = result
        }

        data class Polling<T>(
            val reason: String,
            override val lastPolledResult: Result<T>?,
        ) : PollState<T>
    }
}
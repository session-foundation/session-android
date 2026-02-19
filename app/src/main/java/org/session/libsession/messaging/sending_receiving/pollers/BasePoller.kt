package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.selectUnbiased
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private typealias PollRequestCallback<T> = SendChannel<Result<T>>

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
    private val appVisibilityManager: AppVisibilityManager,
    private val scope: CoroutineScope,
) {
    protected val logTag: String = this::class.java.simpleName

    private val manualPollRequestSender: SendChannel<PollRequestCallback<T>>

    private val mutablePollState = MutableStateFlow<PollState<T>>(PollState.Idle)

    /**
     * The current state of the poller.
     */
    val pollState: StateFlow<PollState<T>> get() = mutablePollState

    init {
        val manualPollRequestChannel = Channel<PollRequestCallback<T>>(capacity = 1)

        manualPollRequestSender = manualPollRequestChannel

        scope.launch {
            var numConsecutiveFailures = 0
            var nextRoutinePollAt: TimeMark? = null

            while (true) {
                val (pollReason, callback) = selectUnbiased {
                    manualPollRequestChannel.onReceive { callback ->
                        "manual" to callback
                    }

                    waitForRoutinePoll(nextRoutinePollAt).onAwait {
                        "routine" to null
                    }
                }

                val result = runCatching {
                    pollOnce(pollReason)
                }.onSuccess { numConsecutiveFailures = 0 }
                    .onFailure {
                        if (it is CancellationException) throw it
                        numConsecutiveFailures += 1
                    }

                // Must use trySend as we shouldn't be waiting or responsible for
                // the manual request (potential) ill-setup.
                callback?.trySend(result)

                val nextPollSeconds = nextPollDelaySeconds(numConsecutiveFailures)
                nextRoutinePollAt = TimeSource.Monotonic.markNow().plus(nextPollSeconds.seconds)
            }
        }
    }

    private fun waitForRoutinePoll(minDelay: TimeMark?): Deferred<Unit> {
        return scope.async {
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
                { _, _ -> }
            ).first()

            // At this point, the criteria for routine poll are all satisfied.

            // If we are told we can only start executing from a time, wait until that.
            val delayDuration = minDelay?.elapsedNow()?.let { -it.inWholeMilliseconds }
            if (delayDuration != null && delayDuration > 0) {
                Log.d(logTag, "Delay next poll for ${delayDuration}ms")
                delay(delayDuration)
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
     * @param isFirstPollSinceAppStarted True if this is the first poll since the app started.
     * @return The result of the polling operation.
     */
    protected abstract suspend fun doPollOnce(isFirstPollSinceAppStarted: Boolean): T

    private suspend fun pollOnce(reason: String): T {
        val lastState = mutablePollState.value
        mutablePollState.value =
            PollState.Polling(reason, lastPolledResult = lastState.lastPolledResult)
        Log.d(logTag, "Start $reason polling")
        val result = runCatching {
            doPollOnce(isFirstPollSinceAppStarted = lastState is PollState.Idle)
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

    /**
     * Manually triggers a single polling operation.
     *
     * Note:
     * * If a polling operation is already in progress, this will wait for it to complete first.
     * * This method does not check for app foreground/background state or network connectivity.
     * * This method will throw if the polling operation fails.
     */
    suspend fun manualPollOnce(): T {
        val callback = Channel<Result<T>>(capacity = 1)
        manualPollRequestSender.send(callback)
        return callback.receive().getOrThrow()
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
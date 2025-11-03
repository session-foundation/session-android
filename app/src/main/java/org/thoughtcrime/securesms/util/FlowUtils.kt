package org.thoughtcrime.securesms.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Buffers items from the flow and emits them in batches. The batch will have size [maxItems] and
 * time [timeoutMillis] limit.
 */
fun <T> Flow<T>.timedBuffer(timeoutMillis: Long, maxItems: Int): Flow<List<T>> {
    return channelFlow {
        val buffer = mutableListOf<T>()
        var bufferBeganAt = -1L

        collectLatest { value ->
            if (buffer.isEmpty()) {
                bufferBeganAt = System.currentTimeMillis()
            }

            buffer.add(value)

            if (buffer.size < maxItems) {
                // If the buffer is not full, wait until the time limit is reached.
                // The delay here, as a suspension point, will be cancelled by `collectLatest`,
                // if another item is collected while we are waiting for the `delay` to complete.
                // Once the delay is cancelled, another round of `collectLatest` will be restarted.
                delay((System.currentTimeMillis() + timeoutMillis - bufferBeganAt).coerceAtLeast(0L))
            }

            // When we reach here, it's either the buffer is full, or the timeout has been reached:
            // send out the buffer and reset the state
            send(buffer.toList())
            buffer.clear()
        }
    }
}

fun <T, R> Flow<T>.mapToStateFlow(
    scope: CoroutineScope,
    initialData: T,
    valueGetter: (T) -> R
): StateFlow<R> {
    return map { valueGetter(it) }
        .stateIn(scope, SharingStarted.Eagerly, valueGetter(initialData))
}

fun <T, R> StateFlow<T>.mapStateFlow(
    scope: CoroutineScope,
    valueGetter: (T) -> R
): StateFlow<R> {
    return map { valueGetter(it) }
        .stateIn(scope, SharingStarted.Eagerly, valueGetter(value))
}

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<Iterable<T>>.flatten(): Flow<T> = flatMapConcat { it.asFlow() }

/**
 * Erase the type of the flow, so that it can be combined with other flows of different types
 * easily. Useful for pushing out notification but you don't care about the values themselves.
 */
fun <T> Flow<T>.castAwayType(): Flow<*> = this as Flow<*>
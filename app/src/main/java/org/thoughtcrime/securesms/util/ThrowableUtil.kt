package org.thoughtcrime.securesms.util

/**
 * Walk the cause chain of this throwable. This chain includes itself as the first element.
 */
fun Throwable.causes(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causes
    while (current != null) {
        yield(current)
        current = current.cause
    }
}

/**
 * Find out if this throwable as a root cause of the specified type, if so return it.
 */
inline fun <reified E: Throwable> Throwable.getRootCause(): E? {
    return causes()
        .filterIsInstance<E>()
        .firstOrNull()
}
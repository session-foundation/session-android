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
 * Find out if this throwable or any of its causes is of type [E], returning the first one found or null.
 */
inline fun <reified E> Throwable.findCause(): E? {
    return causes()
        .filterIsInstance<E>()
        .firstOrNull()
}
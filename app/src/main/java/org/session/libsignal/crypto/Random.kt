package org.session.libsignal.crypto

import org.session.libsignal.utilities.Util.SECURE_RANDOM
import java.security.SecureRandom

/**
 * Uses `SecureRandom` to pick an element from this collection.
 */
fun <T> Collection<T>.secureRandomOrNull(): T? {
    if (isEmpty()) return null
    val index = SECURE_RANDOM.nextInt(size) // SecureRandom should be cryptographically secure
    return elementAtOrNull(index)
}

/**
 * Uses `SecureRandom` to pick an element from this collection.
 *
 * @throws [NullPointerException] if the [Collection] is empty
 */
fun <T> Collection<T>.secureRandom(): T {
    return secureRandomOrNull()!!
}

fun <T> Collection<T>.shuffledRandom(): List<T> = shuffled(SECURE_RANDOM)

/**
 * Generates a sequence that yields the elements of this list in a random order.
 */
fun <T> List<T>.shuffledSequence(): Sequence<T> {
    val random = SecureRandom()
    return generateSequence { random.nextInt(size) }
        .distinct()
        .map { this[it] }
}

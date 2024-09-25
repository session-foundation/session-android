package org.session.libsession.messaging.sending_receiving.pollers

/**
 * Exception thrown by a Poller-family when multiple error could have occurred.
 */
class PollerException(message: String, errors: List<Throwable>) : RuntimeException(
    message,
    errors.firstOrNull()
) {
    init {
        errors.asSequence()
            .drop(1)
            .forEach(this::addSuppressed)
    }
}

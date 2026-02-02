package org.thoughtcrime.securesms.api.error

import org.session.libsession.network.model.FailureDecision

/**
 * An [RuntimeException] that indicates an error has occurred and a decision has been made
 * along the pathway on how to handle the failure. This should be a final decision on this operation
 * so that the upmost layers can make decisions based on this.
 */
class ErrorWithFailureDecision(
    cause: Throwable?,
    val failureDecision: FailureDecision
) : RuntimeException("$failureDecision: ${cause?.message}", cause)

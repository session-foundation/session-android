package org.session.libsession.network.model

sealed class FailureDecision {
    data object Retry : FailureDecision()
    data class Fail(val throwable: Throwable) : FailureDecision()
}
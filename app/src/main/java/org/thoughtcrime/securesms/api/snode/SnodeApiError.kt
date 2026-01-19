package org.thoughtcrime.securesms.api.snode

interface SnodeApiError {
    data class UnknownStatusCode(val code: Int, val bodyText: String?)
        : SnodeApiError, RuntimeException("Unknown status code $code") {
        init {
            check(code !in 200..299) { "Status code $code indicates success, not an error." }
        }
    }
}
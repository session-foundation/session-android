package org.thoughtcrime.securesms.api.server

sealed interface ServerApiError {
    class UnknownStatusCode(val code: Int, val bodyText: String?) : ServerApiError, RuntimeException(
        "Unknown status code $code with body: $bodyText"
    )
}

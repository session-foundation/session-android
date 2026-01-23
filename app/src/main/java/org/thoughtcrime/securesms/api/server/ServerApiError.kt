package org.thoughtcrime.securesms.api.server

import org.thoughtcrime.securesms.api.http.HttpBody

sealed interface ServerApiError {
    class UnknownStatusCode(
        val api: Class<out ServerApi<*>>,
        val code: Int,
        val body: HttpBody?
    ) : ServerApiError, RuntimeException(
        "Unknown status code on ${api.simpleName} with code = $code with body: $body"
    )
}

package org.thoughtcrime.securesms.api.http

import org.thoughtcrime.securesms.api.error.UnknownStatusCodeException

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: HttpBody,
) {
    fun getHeader(name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    fun throwIfNotSuccessful(): HttpResponse {
        if (statusCode !in 200..299) {
            throw UnknownStatusCodeException(
                code = statusCode,
                origin = "Unknown",
                bodyText = body.toText()
            )
        }

        return this
    }
}
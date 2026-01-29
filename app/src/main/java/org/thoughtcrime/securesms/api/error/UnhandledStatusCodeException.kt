package org.thoughtcrime.securesms.api.error

/**
 * An exception indicating that the HTTP status code received is
 * erroneous or unrecognized. This exception is normally thrown when none of the
 * [org.thoughtcrime.securesms.api.ApiExecutor] layers know how to handle the status code.
 *
 * Normally this is up to the caller to handle.
 */
class UnhandledStatusCodeException(
    val code: Int,
    val origin: String,
    val bodyText: String? = null
) : RuntimeException("Unhandled HTTP status code $code from $origin: body=\"${bodyText.orEmpty()}\"") {
    init {
        check(code !in 200..299) {
            "HTTP status code $code indicates success, cannot be used with ${this.javaClass.simpleName}"
        }
    }
}

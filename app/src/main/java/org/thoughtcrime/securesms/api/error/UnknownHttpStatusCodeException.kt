package org.thoughtcrime.securesms.api.error

class UnknownHttpStatusCodeException(
    val code: Int,
    val origin: String,
    val bodyText: String? = null
) : RuntimeException("Unknown HTTP status code $code from $origin") {
    init {
        check(code !in 200..299) {
            "HTTP status code $code indicates success, cannot be used with UnknownHttpStatusCodeException"
        }
    }
}

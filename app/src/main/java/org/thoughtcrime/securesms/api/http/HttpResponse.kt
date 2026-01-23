package org.thoughtcrime.securesms.api.http

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: HttpBody,
) {
    fun getHeader(name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}
package org.thoughtcrime.securesms.api.http

import okhttp3.HttpUrl

data class HttpRequest(
    val url: HttpUrl,
    val method: String,
    val headers: Map<String, String>,
    val body: HttpBody?,
) {
    init {
        check(method != "GET" || body == null) { "GET request cannot have a body" }
    }

    fun getHeader(name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}
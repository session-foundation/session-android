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

    companion object {
        fun createFromJson(
            url: HttpUrl,
            method: String,
            jsonText: String
        ): HttpRequest {
            val body = HttpBody.Text(jsonText)

            return HttpRequest(
                url = url,
                method = method,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Content-Length" to body.byteLength.toString()
                ),
                body = body,
            )
        }
    }
}
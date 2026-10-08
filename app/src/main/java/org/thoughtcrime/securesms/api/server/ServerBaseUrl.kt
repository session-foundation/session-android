package org.thoughtcrime.securesms.api.server

/**
 * Strips trailing slashes from a server base URL so a path with a leading slash can be appended to it.
 *
 * Every `buildRequest` builds its URL as `"$baseUrl/$path"`, which assumes the base does NOT end in a
 * slash. That assumption is quietly false whenever the base came from an OkHttp `HttpUrl`: for a host-only
 * URL, `HttpUrl.toString()` always emits a trailing slash, so `http://host:8000` comes back as
 * `http://host:8000/` and the result is `http://host:8000//file`.
 *
 * Normalised here, once, where a base URL enters a request, rather than at each place a path is appended —
 * there are several of those already and the next one added would repeat the mistake.
 *
 * Servers route the doubled path fine, which is why this survived: nothing fails. It is still worth
 * getting right, because the path shape identifies the client on the wire, and anything path-sensitive in
 * front of the server (proxy, cache key, path-based rate limiting) may treat `//file` and `/file` as two
 * different resources.
 */
fun String.trimTrailingSlashes(): String = trimEnd('/')

package org.thoughtcrime.securesms.api.server

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Server base URLs must not end in a slash, because every `buildRequest` appends `"/$path"` to them.
 *
 * The reason this needs pinning: servers route the doubled path perfectly well and return 200, so getting
 * it wrong breaks nothing and nothing fails. It regresses silently or not at all.
 */
class ServerBaseUrlTest {

    // --- the premise, verified here rather than assumed --------------------------------------------

    @Test
    fun `OkHttp adds a trailing slash to a host-only URL`() {
        // This is where the bug came from, and it is a property of HttpUrl rather than of any config
        // value -- so it applies to the shipped default file server exactly as much as to a QA one.
        assertEquals("http://filev2.getsession.org/", "http://filev2.getsession.org".toHttpUrl().toString())
        assertEquals("http://localhost:8000/", "http://localhost:8000".toHttpUrl().toString())
    }

    @Test
    fun `appending to an unnormalised host-only URL is what produced the doubled slash`() {
        // Documents the defect this fixes: the shape the file server was actually seeing.
        val baseUrl = "http://localhost:8000".toHttpUrl().toString()

        assertEquals("http://localhost:8000//file", "$baseUrl/file")
    }

    // --- the fix -----------------------------------------------------------------------------------

    @Test
    fun `a host-only URL yields a single slash once normalised`() {
        val baseUrl = "http://localhost:8000".toHttpUrl().toString().trimTrailingSlashes()

        assertEquals("http://localhost:8000/file", "$baseUrl/file")
    }

    @Test
    fun `a URL that already has no trailing slash is unchanged`() {
        // Community base URLs arrive as plain config strings and are already correct; normalising must be
        // a no-op for them rather than eating a character.
        assertEquals(
            "http://open.getsession.org",
            "http://open.getsession.org".trimTrailingSlashes()
        )
    }

    @Test
    fun `a base URL with a path keeps that path`() {
        assertEquals(
            "http://host:8000/sogs",
            "http://host:8000/sogs/".trimTrailingSlashes()
        )
    }

    @Test
    fun `repeated trailing slashes are all removed`() {
        // A user-entered community URL is one plausible source of these.
        assertEquals("http://host:8000", "http://host:8000///".trimTrailingSlashes())
    }

    @Test
    fun `normalising is idempotent`() {
        val once = "http://localhost:8000".toHttpUrl().toString().trimTrailingSlashes()

        assertEquals(once, once.trimTrailingSlashes())
    }

    // --- the real request path ----------------------------------------------------------------------

    @Test
    fun `a request built from an HttpUrl-derived base exposes it without a trailing slash`() {
        // The end-to-end shape: ServerApiRequest is where a base URL enters, and this is the property the
        // four buildRequest implementations rely on.
        val request = ServerApiRequest(
            serverBaseUrl = "http://localhost:8000".toHttpUrl().toString(),
            serverX25519PubKeyHex = "00",
            api = mock<ServerApi<Any>>(),
        )

        assertEquals("http://localhost:8000", request.serverBaseUrl)
        assertEquals("http://localhost:8000/file", "${request.serverBaseUrl}/file")
    }
}

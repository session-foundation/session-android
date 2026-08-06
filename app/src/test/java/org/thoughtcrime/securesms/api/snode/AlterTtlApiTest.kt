package org.thoughtcrime.securesms.api.snode

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.Test
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.util.MockLoggingRule
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the `expire` request as it actually goes **on the wire**, and the response as it actually
 * comes back — rather than what the caller passed in.
 *
 * That distinction is the whole point here. Three separate instances of the same bug have now been
 * found across the Session clients: a flag accepted at one layer and silently dropped before the wire,
 * with no error anywhere (Desktop's `extends` typo, Desktop's dead `shortenOrExtend` parameter, iOS's
 * `updateExpiry` dropping `shortenOnly`/`extendOnly`). A test asserting on the caller's argument would
 * have caught none of them. And `extend` reaching the server is load bearing for detection: without it
 * the server omits `unchanged` entirely, and every healthy hash then reads as missing.
 */
class AlterTtlApiTest {
    @get:Rule
    val loggingRule = MockLoggingRule()

    private val h1 = "hash-one"
    private val h2 = "hash-two"

    private val json = Json { ignoreUnknownKeys = true }
    private val userId = AccountId(IdPrefix.STANDARD, ByteArray(32) { 1 })
    private val snode = Snode(
        url = "https://snode.example".toHttpUrl(),
        publicKeySet = Snode.KeySet("k1", "k2"),
    )

    @Test
    fun `an extend request puts extend true on the wire`() {
        val params = buildParams(AlterTtlApi.AlterType.Extend)

        assertEquals(true, params.bool("extend"))
        assertNull(params["shorten"])
    }

    @Test
    fun `a shorten request puts shorten true on the wire, and no extend`() {
        val params = buildParams(AlterTtlApi.AlterType.Shorten)

        assertEquals(true, params.bool("shorten"))
        assertNull(params["extend"])
    }

    @Test
    fun `an unspecified request sets neither flag`() {
        val params = buildParams(AlterTtlApi.AlterType.Unspecified)

        assertNull(params["extend"])
        assertNull(params["shorten"])
    }

    @Test
    fun `the requested hashes and the new expiry go on the wire`() {
        val params = buildParams(AlterTtlApi.AlterType.Extend)

        assertEquals(
            listOf(h1, h2),
            (params.getValue("messages") as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals("12345", (params.getValue("expiry") as JsonPrimitive).content)
    }

    @Test
    fun `a real expire response is read for missing hashes`() = runTest {
        val report = handle(
            AlterTtlApi.AlterType.Extend,
            """
            {
              "swarm": {
                "aa": { "updated": ["$h1"], "unchanged": {}, "expiry": 12345, "signature": "sig" },
                "bb": { "updated": ["$h1", "$h2"], "unchanged": {}, "expiry": 12345, "signature": "sig" },
                "cc": { "failed": true, "timeout": true }
              },
              "t": 999
            }
            """.trimIndent()
        )

        // "aa" has h2 in neither array, and one eligible snode reporting absence is enough. "cc"
        // contributes nothing at all.
        assertEquals(ConfigExpiryReport.Checked(setOf(h2)), report)
    }

    @Test
    fun `a shorten response is never read for missing hashes`() = runTest {
        // The body is deliberately **fully readable** — `unchanged` present and empty — so the shorten
        // short-circuit is the ONLY thing that can produce Inconclusive here. With an absent `unchanged`
        // key (the first version of this fixture) the response is unreadable anyway, and the test passed
        // whether or not the alter type was checked at all: it would have gone green against an
        // implementation that read shorten responses for absence. Read this way it discriminates —
        // drop the alter-type guard and both hashes come back as Checked missing.
        //
        // ⚠️ Deliberately counterfactual: a real shorten response omits `unchanged`, so production trips
        // both causes together and no realistic fixture can isolate either one. Since the assertion now
        // names the cause, "correcting" this body to omit `unchanged` fails the test LOUDLY (it would come
        // back NoUsableSubResponse) rather than passing vacuously — which is the whole point of the causes
        // being distinguishable, and why the fixture and the assertion have to be read together.
        val report = handle(
            AlterTtlApi.AlterType.Shorten,
            """{ "swarm": { "aa": { "updated": [], "unchanged": {}, "expiry": 1 } } }"""
        )

        assertEquals(ConfigExpiryReport.Inconclusive.ExtendNotRequested, report)
    }

    /**
     * The expiries have already been altered by the time we read the body — that is the request's
     * actual job. Detection is a bonus read of the same response, so a surprise in its shape must not
     * turn a successful alteration into a failed request.
     */
    @Test
    fun `an unreadable response degrades to inconclusive instead of failing the request`() = runTest {
        val report = handle(AlterTtlApi.AlterType.Extend, """{ "swarm": "not-a-dict" }""")

        // ResponseUnreadable, not NoUsableSubResponse: detection never ran at all here. Asserting the
        // specific cause is what proves the degradation happened in the decode and not somewhere inside
        // the rules.
        assertEquals(ConfigExpiryReport.Inconclusive.ResponseUnreadable, report)
    }

    private fun api(alterType: AlterTtlApi.AlterType) = AlterTtlApi(
        messageHashes = listOf(h1, h2),
        auth = mockk<SwarmAuth>().also {
            every { it.accountId } returns userId
            every { it.ed25519PublicKeyHex } returns null
            every { it.sign(any()) } returns mapOf("signature" to "a-signature")
        },
        alterType = alterType,
        newExpiry = 12345L,
        errorManager = mockk(relaxed = true),
        snodeClock = mockk<SnodeClock>().also { every { it.currentTimeMillis() } returns 555L },
        json = json,
    )

    private fun buildParams(alterType: AlterTtlApi.AlterType): JsonObject {
        val params = api(alterType).buildParams(ApiExecutorContext()) as JsonObject

        // Whatever else changes, the request must stay authenticated.
        assertTrue("signature" in params)
        return params
    }

    private suspend fun handle(alterType: AlterTtlApi.AlterType, body: String): ConfigExpiryReport =
        api(alterType)
            .handleResponse(ApiExecutorContext(), snode, 200, Json.parseToJsonElement(body))
            .expiry

    private fun JsonObject.bool(key: String) = (getValue(key) as JsonPrimitive).boolean
}

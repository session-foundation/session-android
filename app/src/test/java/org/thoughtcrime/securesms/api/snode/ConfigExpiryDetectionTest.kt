package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals

/**
 * How an `expire` response is read: which of the hashes we asked about the swarm has lost.
 *
 * The V-numbers in the test names are a vocabulary shared with the other Session clients, which
 * implement this detection separately from the same set of cases. Keeping the numbering aligned is what
 * lets a disagreement between two clients be pinned to one specific rule rather than a whole feature;
 * they are not references to anything outside this repo.
 *
 * V10-V13 cover the guards around the recovery action rather than the reading of the response, so
 * they live in [org.thoughtcrime.securesms.configs.ExpiredConfigRecoveryTest].
 */
class ConfigExpiryDetectionTest {
    private val h1 = "hash-one"
    private val h2 = "hash-two"
    private val requested = listOf(h1, h2)

    @Test
    fun `V1 - everything updated means nothing missing`() {
        val report = detect(
            "snodeA" to SnodeExpiryState(updated = listOf(h1, h2), unchanged = emptyMap()),
        )

        assertEquals(ConfigExpiryReport.Checked(emptySet()), report)
    }

    @Test
    fun `V2 - unchanged counts as present`() {
        val report = detect(
            "snodeA" to SnodeExpiryState(updated = listOf(h1), unchanged = mapOf(h2 to 1L)),
        )

        assertEquals(ConfigExpiryReport.Checked(emptySet()), report)
    }

    @Test
    fun `V3 - absent from both arrays is missing`() {
        val report = detect(
            "snodeA" to SnodeExpiryState(updated = listOf(h1), unchanged = emptyMap()),
        )

        assertEquals(ConfigExpiryReport.Checked(setOf(h2)), report)
    }

    @Test
    fun `V4 - one snode reporting absence is enough, even when another has it`() {
        val report = detect(
            "snodeA" to SnodeExpiryState(updated = listOf(h1), unchanged = emptyMap()),
            "snodeB" to SnodeExpiryState(updated = listOf(h1, h2), unchanged = emptyMap()),
        )

        assertEquals(ConfigExpiryReport.Checked(setOf(h2)), report)
    }

    @Test
    fun `V5 - a failed sub-response is excluded, not read as absence`() {
        val report = detect(
            "snodeA" to SnodeExpiryState(updated = listOf(h1, h2), unchanged = emptyMap()),
            "snodeB" to SnodeExpiryState(failed = true),
        )

        assertEquals(ConfigExpiryReport.Checked(emptySet()), report)
    }

    @Test
    fun `V6 - all sub-responses failed is inconclusive`() {
        val report = detect(
            "snodeA" to SnodeExpiryState(failed = true),
            "snodeB" to SnodeExpiryState(failed = true),
        )

        assertEquals(ConfigExpiryReport.Inconclusive.NoUsableSubResponse, report)
    }

    /**
     * V7 and V15 are the same wire response, and the point of testing it is the distinction it draws
     * with V8: `unchanged: {}` is a *valid answer* meaning "I hold none of the rest", so total loss
     * must come out as "everything missing" rather than being mistaken for unavailability — which
     * would disable recovery in precisely the case it exists for.
     */
    @Test
    fun `V7 and V15 - a snode holding nothing reports every hash missing`() {
        val report = detect(
            "snodeA" to SnodeExpiryState(updated = emptyList(), unchanged = emptyMap()),
        )

        assertEquals(ConfigExpiryReport.Checked(setOf(h1, h2)), report)
    }

    /**
     * V8 — no extend was asked for, so the response says nothing about absence whatever it contains.
     *
     * The sub-response is deliberately **readable** (`unchanged` present and empty) so that the extend
     * flag is the only thing that can produce Inconclusive. The first version passed `unchanged = null`,
     * which is unreadable on its own (V8b) — so the test went green whether or not the extend flag was
     * consulted at all, and a mutation deleting that guard left it passing. It now fails on that mutation.
     *
     * ⚠️ **This fixture is deliberately counterfactual and must stay that way.** A real server omits
     * `unchanged` when it decides the request wasn't an extend, so production triggers *both* causes at
     * once — which is exactly why the realistic fixture cannot isolate either. Making this "accurate" by
     * restoring `unchanged = null` re-breaks the test silently and it will still pass. The realistic shape
     * is covered by V8b, whose subject *is* the unreadable response.
     */
    @Test
    fun `V8 - detection is unavailable without an extend request`() {
        val report = detectMissingConfigHashes(
            requestedHashes = requested,
            extendRequested = false,
            swarm = mapOf("snodeA" to SnodeExpiryState(updated = listOf(h1), unchanged = emptyMap())),
        )

        assertEquals(ConfigExpiryReport.Inconclusive.ExtendNotRequested, report)
    }

    /**
     * The same trap as V8, but reached the other way round: the server omits `unchanged` whenever it
     * decides the request wasn't an extend, and a group member gets extend-only forced on server-side
     * *while* the array is suppressed. A response we can't read must not be read as "all gone".
     */
    @Test
    fun `V8b - a missing unchanged key is unavailability, not absence`() {
        val report = detect(
            "snodeA" to SnodeExpiryState(updated = listOf(h1), unchanged = null),
        )

        assertEquals(ConfigExpiryReport.Inconclusive.NoUsableSubResponse, report)
    }

    @Test
    fun `V8c - an unreadable sub-response is skipped while a readable one still counts`() {
        val report = detect(
            "snodeA" to SnodeExpiryState(updated = listOf(h1), unchanged = null),
            "snodeB" to SnodeExpiryState(updated = listOf(h1), unchanged = emptyMap()),
        )

        assertEquals(ConfigExpiryReport.Checked(setOf(h2)), report)
    }

    @Test
    fun `V9 - each part of a multipart config is judged on its own hash`() {
        val report = detectMissingConfigHashes(
            requestedHashes = listOf("P1", "P2", "P3"),
            extendRequested = true,
            swarm = mapOf(
                "snodeA" to SnodeExpiryState(updated = listOf("P1", "P3"), unchanged = emptyMap()),
            ),
        )

        // Only P2 is re-stored, and the config is emphatically *not* healthy just because two of its
        // three parts are — a partially present multipart config decodes to nothing.
        assertEquals(ConfigExpiryReport.Checked(setOf("P2")), report)
    }

    /**
     * The rules above are only worth anything if the wire format actually preserves the distinction
     * they hinge on, so parse it rather than hand-building the states: `"unchanged": {}` means "I
     * modified everything I hold", while no `unchanged` key at all means "you can't tell from this".
     */
    @Test
    fun `an absent unchanged key parses differently to an empty one`() {
        val json = Json { ignoreUnknownKeys = true }

        val parsed: SwarmResponse = json.decodeFromJsonElement(
            Json.parseToJsonElement(
                """
                {
                  "swarm": {
                    "empty":   { "updated": ["hash-one"], "unchanged": {}, "expiry": 123, "signature": "sig" },
                    "absent":  { "updated": ["hash-one"], "expiry": 123, "signature": "sig" },
                    "holding": { "updated": ["hash-one"], "unchanged": { "hash-two": 456 } },
                    "failed":  { "failed": true, "timeout": true }
                  },
                  "t": 1234
                }
                """.trimIndent()
            ).jsonObject
        )

        assertEquals(emptyMap(), parsed.swarm.getValue("empty").unchanged)
        assertEquals(null, parsed.swarm.getValue("absent").unchanged)
        assertEquals(mapOf(h2 to 456L), parsed.swarm.getValue("holding").unchanged)
        assertEquals(true, parsed.swarm.getValue("failed").failed)

        // ...and the two together behave as V8c says they should.
        assertEquals(
            ConfigExpiryReport.Checked(setOf(h2)),
            detectMissingConfigHashes(
                requestedHashes = requested,
                extendRequested = true,
                swarm = parsed.swarm - "holding",
            )
        )
    }

    // --- Which mechanism decides that a group has expired ---

    @Test
    fun `V16 - every requested keys hash gone means the group is expired`() {
        assertEquals(
            true,
            groupExpiredFromExpiryCheck(
                ConfigExpiryReport.Checked(setOf("keys-1")),
                keysHashes = setOf("keys-1"),
            )
        )
    }

    /**
     * V16a — the threshold is *every* keys hash, not any. With only one keys hash V16 can't tell the
     * two apart, and a device legitimately holds several: a generation is one rekey message plus N
     * per-member key supplements.
     *
     * A surviving keys message means existing members are unaffected, and the banner's remedy isn't
     * free — an admin rekey dirties info and members and bumps both seqnos — so a spurious flag causes
     * spurious writes. The accepted residual: a supplement is written when a member is *added*, so it
     * can outlive its generation's rekey message, and a new device that isn't one of the session ids
     * that supplement encrypts to can't derive the generation while the group still reads as healthy.
     */
    @Test
    fun `V16a - a surviving keys hash clears the expired state even if another is gone`() {
        assertEquals(
            false,
            groupExpiredFromExpiryCheck(
                ConfigExpiryReport.Checked(setOf("keys-1")),
                keysHashes = setOf("keys-1", "keys-2"),
            )
        )
    }

    @Test
    fun `V19 - a missing info hash while the keys survive does not expire the group`() {
        // GroupInfo's hash is gone, so it gets re-stored — but the banner is the keys config's call
        // alone, and the keys hash is present.
        assertEquals(
            false,
            groupExpiredFromExpiryCheck(
                ConfigExpiryReport.Checked(setOf("info-1")),
                keysHashes = setOf("keys-1"),
            )
        )
    }

    /**
     * V14 — a response to a request that asked about nothing is **inconclusive**, not a conclusive
     * "nothing is missing".
     *
     * `Checked(emptySet())` is the natural short-circuit and it is wrong in a way that hides itself: a
     * conclusive report outranks the caller's fallback checks, so detection would become the authority
     * for exactly the case it is meant to defer on, and the fallback would be unreachable. All three
     * Session clients wrote this short-circuit independently, two of them with a test asserting the
     * wrong value — so this test earns its place despite looking trivial.
     */
    @Test
    fun `V14 - an empty ask is inconclusive, not a conclusive nothing-missing`() {
        val report = detectMissingConfigHashes(
            requestedHashes = emptyList(),
            extendRequested = true,
            swarm = mapOf("snodeA" to SnodeExpiryState(updated = emptyList(), unchanged = emptyMap())),
        )

        assertEquals(ConfigExpiryReport.Inconclusive.NothingAsked, report)
    }

    @Test
    fun `V16b - holding no keys hashes leaves the expired state to the empty-fetch check`() {
        // No hashes means no expire request was sent at all, so there is nothing to conclude. Null,
        // not false.
        assertEquals(
            null,
            groupExpiredFromExpiryCheck(
                ConfigExpiryReport.Checked(emptySet()),
                keysHashes = emptySet(),
            )
        )
    }

    /**
     * Every cause, not a sampled one: the causes are distinguishable precisely so a test can name them, and
     * a new cause added later must not quietly acquire the power to flag a group expired.
     */
    @Test
    fun `an inconclusive check leaves the expired state alone, whatever made it inconclusive`() {
        val causes = listOf(
            ConfigExpiryReport.Inconclusive.ExtendNotRequested,
            ConfigExpiryReport.Inconclusive.NothingAsked,
            ConfigExpiryReport.Inconclusive.NoUsableSubResponse,
            ConfigExpiryReport.Inconclusive.ResponseUnreadable,
        )

        for (cause in causes) {
            assertEquals(null, groupExpiredFromExpiryCheck(cause, keysHashes = setOf("keys-1")), "$cause")
        }
    }

    @Test
    fun `no check at all leaves the expired state alone`() {
        assertEquals(null, groupExpiredFromExpiryCheck(null, keysHashes = setOf("keys-1")))
    }

    private fun detect(vararg swarm: Pair<String, SnodeExpiryState>) =
        detectMissingConfigHashes(
            requestedHashes = requested,
            extendRequested = true,
            swarm = swarm.toMap(),
        )

    /** Mirrors the private response type in [AlterTtlApi]. */
    @Serializable
    private class SwarmResponse(val swarm: Map<String, SnodeExpiryState> = emptyMap())
}

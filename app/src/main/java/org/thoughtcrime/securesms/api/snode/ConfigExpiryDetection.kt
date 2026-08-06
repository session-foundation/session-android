package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.Serializable

/**
 * Interpretation of an `expire` response: which of the requested message hashes the swarm no longer
 * holds.
 *
 * The `expire` RPC is recursive: one snode fans the request out to the whole swarm and returns a
 * `swarm` dict keyed by snode pubkey, where each entry is that snode's own answer. An entry reports
 * `updated` (hashes whose expiry row it actually modified) and `unchanged` (hashes it still holds
 * but didn't modify), so a hash in *neither* array means that snode's database has no such message.
 *
 * @see detectMissingConfigHashes for the rules, which are subtle enough to be worth reading.
 */
sealed interface ConfigExpiryReport {
    /**
     * The response can't tell us anything, and **no hash may be treated as missing**. Every consumer
     * treats these identically — the distinction is not a behavioural one, it exists so that a test can
     * name *which* condition it is exercising.
     *
     * That is not cosmetic. When several conditions share one value, a fixture set up for one of them
     * usually satisfies another as well, and the test then passes without touching the guard it names:
     * two tests here did exactly that, and both went green with their guard deleted. Distinct values make
     * every fixture self-isolating, because asserting the cause *is* asserting the guard ran.
     */
    sealed interface Inconclusive : ConfigExpiryReport {
        /**
         * No extension was requested, so the server omits `unchanged` by definition and the response says
         * nothing about absence whatever else it contains.
         */
        data object ExtendNotRequested : Inconclusive

        /** We asked about no hashes, so there is nothing the answer could be about. */
        data object NothingAsked : Inconclusive

        /**
         * Every sub-response was unusable — each one either `failed` or omitted `unchanged`. Distinct from
         * [ExtendNotRequested] because here we *did* ask and the swarm still told us nothing.
         */
        data object NoUsableSubResponse : Inconclusive

        /**
         * The response body couldn't be decoded at all, so detection never ran. Deliberately *not* folded
         * into [NoUsableSubResponse]: that one means the swarm answered and told us nothing, this one means
         * we failed to read what it said. Collapsing the two would reintroduce, in the type meant to
         * prevent it, exactly the ambiguity this split exists to remove.
         */
        data object ResponseUnreadable : Inconclusive
    }

    /** At least one snode gave a usable answer. [missingHashes] may be empty, meaning all healthy. */
    data class Checked(val missingHashes: Set<String>) : ConfigExpiryReport
}

/**
 * One snode's answer within a recursive `expire` response.
 *
 * [unchanged] is nullable *and that matters*: the server only includes the key at all when the
 * request set `extend` or `shorten`, so an absent key means "this response can't be used for
 * detection", whereas a present-but-empty one means "I modified everything I hold".
 */
@Serializable
class SnodeExpiryState(
    val failed: Boolean = false,
    val updated: List<String> = emptyList(),
    val unchanged: Map<String, Long>? = null,
)

/**
 * Whether an expiry check is authoritative about a group having expired, and if so what it says.
 *
 * The group keys config decides this on its own. It is the only one of the three whose absence is both
 * unambiguous, and not repairable by this device *yet*: libsession retains the bytes of active keys
 * messages, but the Android wrapper exposes no binding for them, so there is no way from Kotlin to re-emit keys that
 * have already been loaded, so unlike info and members there is no recovery to wait for before
 * flagging. Info or members going missing drives a re-store, never the banner.
 *
 * The result is deliberately three-valued, because this check does not supersede the existing "we
 * merged config messages and ended up with no keys at all" one — they answer different questions:
 *
 * - `true` / `false` — every requested keys hash is gone, or at least one survives. Detection wins.
 * - `null` — detection has nothing to say, so the existing check decides. Either no eligible snode
 *   answered, or the device held no keys hashes to ask about in the first place, in which case no
 *   request was even sent. Silence here is *not* "nothing is missing".
 *
 * @param keysHashes the group keys hashes that were requested — kept separate from the info and
 *  members hashes on purpose, since a flat union of the three can't be attributed back.
 */
fun groupExpiredFromExpiryCheck(
    report: ConfigExpiryReport?,
    keysHashes: Set<String>,
): Boolean? {
    if (report !is ConfigExpiryReport.Checked || keysHashes.isEmpty()) {
        return null
    }

    return keysHashes.all { it in report.missingHashes }
}

/**
 * Works out which of [requestedHashes] the swarm has lost, given the per-snode answers in
 * [swarm].
 *
 * The rules, in the order they bite:
 *
 * 1. Detection only works on a request that asked to extend. Without `extend` (or `shorten`) the
 *    server omits `unchanged` entirely, so every hash it didn't touch would look absent — which for
 *    a healthy config is *all of them*. A group member is the dangerous case: their subaccount
 *    lacks delete access, so the server forces extend-only semantics on the update while still
 *    omitting `unchanged`.
 * 2. A sub-response carrying `failed` contributes nothing at all — not evidence of presence, not of
 *    absence. Reading a timeout as "that snode doesn't have it" would turn every network blip into
 *    a re-push storm. If nothing is left to read, the answer is
 *    [ConfigExpiryReport.Inconclusive.NoUsableSubResponse].
 * 3. One usable snode reporting a hash as absent is enough to call it missing; agreement is not
 *    required. Re-storing is idempotent so a false positive costs a request, whereas waiting for
 *    consensus would lean on the swarm replication that is itself the unreliable part.
 *
 * Multipart configs need no special handling here — each part is a separate message with its own
 * hash, so they are simply requested and judged individually. Requiring *all* parts to be present
 * before calling a config healthy is the caller's job.
 */
fun detectMissingConfigHashes(
    requestedHashes: Collection<String>,
    extendRequested: Boolean,
    swarm: Map<String, SnodeExpiryState>,
): ConfigExpiryReport {
    if (!extendRequested) {
        return ConfigExpiryReport.Inconclusive.ExtendNotRequested
    }

    // Asking about nothing tells us nothing. The tempting short-circuit here is
    // `Checked(emptySet())` — "no hashes requested, so none are missing" — which is locally reasonable
    // and wrong: a *conclusive* report outranks the caller's own fallback checks, so this would make
    // detection the authority for precisely the case it is supposed to defer on, and the fallback
    // unreachable. All three Session clients wrote that short-circuit independently.
    if (requestedHashes.isEmpty()) {
        return ConfigExpiryReport.Inconclusive.NothingAsked
    }

    val usable = swarm.values.filter { !it.failed && it.unchanged != null }
    if (usable.isEmpty()) {
        return ConfigExpiryReport.Inconclusive.NoUsableSubResponse
    }

    return ConfigExpiryReport.Checked(
        requestedHashes.filterTo(mutableSetOf()) { hash ->
            usable.any { state -> hash !in state.updated && hash !in state.unchanged!! }
        }
    )
}

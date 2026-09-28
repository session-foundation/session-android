package org.thoughtcrime.securesms.configs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.session.libsignal.utilities.retryWithUniformInterval
import org.thoughtcrime.securesms.api.snode.ConfigExpiryReport
import org.thoughtcrime.securesms.api.snode.everyKeysHashMissing
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsession.utilities.withMutableGroupConfigs
import org.thoughtcrime.securesms.api.snode.DeleteMessageApi
import org.thoughtcrime.securesms.api.snode.RetrieveMessageApi
import org.thoughtcrime.securesms.api.snode.StoreMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.api.swarm.execute
import org.thoughtcrime.securesms.util.AppVisibilityManager
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ExpiredConfigRecovery"

/**
 * Most sub-requests recovery will put in one batch — and therefore also the most it has in flight at once.
 *
 * Derived from the storage server's limit, deliberately not from a concurrency preference. Requests
 * sharing a snode coalesce into one batch inside a 100ms window (`BatchApiExecutor`), which has no size cap
 * or chunking of its own — it flushes whatever accumulated when the deadline fires. The server then rejects
 * an oversized batch whole rather than truncating it (`BATCH_REQUEST_MAX = 20`, `request_handler.h`;
 * `parse_error` in `client_rpc_endpoints.cpp`). So an unchunked round loses *everything*, and it surfaces as
 * a request failure rather than a size error — so it reads as a network problem and gets retried into the
 * same wall.
 *
 * That is easy to reach: `MAX_MULTIPART_SIZE / MAX_MESSAGE_SIZE` means one config can split into ~66 parts,
 * each its own store, and a round batches every config for the swarm together.
 *
 * Both decode paths cap at 20 inclusive and reject 21. They *look* like they disagree — `> MAX` on the
 * JSON path, `>= MAX` on the bt path — but the bt check sits *before* its `push_back` inside the loop, so on
 * the nth request it sees size n-1 and 20 requests never trip it. Reading the operators is not the same as
 * reading the loop.
 *
 * So 20 is the real ceiling and this is set to it. An earlier version used 19 as "headroom", which does not
 * survive scrutiny: the chunker controls the count exactly, so there is nothing for headroom to absorb.
 *
 * A concurrency limiter must never be relied on to bound this — that was the previous arrangement here and
 * it held by coincidence. The boundary has to be explicit and derived from the limit, which is why this one
 * number does both jobs rather than having a separate semaphore that could drift below it.
 */
private const val MAX_BATCH_SUB_REQUESTS = 20

/**
 * How long to leave a swarm alone after its first failed recovery round, doubling per consecutive failure
 * up to [RECOVERY_RETRY_BACKOFF_CEILING_MS] and resetting once anything stores.
 *
 * This is a deferral, never an exclusion, and the distinction is the whole design. A cap on attempts
 * would shut out a device whose stores keep failing for the rest of the session — which is the same
 * population being repaired, so a run of transient failures would cost exactly the wrong devices their
 * recovery. The ceiling therefore bounds the *interval* and never the *number of attempts*: retry stays
 * available indefinitely, it just gets cheaper over time.
 */
private val RECOVERY_RETRY_BACKOFF_MS = 60.seconds.inWholeMilliseconds

/** Upper bound on the *interval* — deliberately not a bound on attempts. See above. */
private val RECOVERY_RETRY_BACKOFF_CEILING_MS = 30.minutes.inWholeMilliseconds

/**
 * How long a successfully re-stored hash stays barred from being re-stored again.
 *
 * Bounded in time rather than scoped to the session, and the reason is the TTL. The bar exists only to
 * stop a swarm that reports the same hash missing on every poll costing a store every poll — a burst
 * measured in seconds. "Never again this session" is unbounded, and a session can outlive the 30-day config
 * TTL: a mobile client backgrounded for a month, or a desktop client, which by design runs for weeks. In
 * that window a hash we successfully put back can expire from the swarm a *second* time, and a
 * session-scoped bar would block the very recovery that should put it back — excluding long-lived sessions,
 * which is exactly where configs expire.
 *
 * 1 hour, and the figure is not load-bearing — the property is "hours". It is still ~100x the margin the
 * bar needs (polls are seconds apart, replication lag seconds to minutes) and 1/720th of the TTL, so it
 * cannot interact with a genuine second expiry.
 *
 * Erring short rather than long because the two failure modes are asymmetric: too long re-creates the exact
 * defect this bound exists to fix, while too short costs a redundant store — and a redundant re-store is
 * byte-identical and idempotent, so it is one request that changes nothing. When one side of a trade costs
 * correctness and the other costs a no-op request, err toward the cheap side. Standardised across the three
 * clients; don't tune it as though something depended on it.
 */
internal val RESTORED_HASH_BAR_MS = 1.hours.inWholeMilliseconds

/**
 * The interval below which this device will not rekey the same group again.
 *
 * Deliberately much longer than the re-store bar, because the trade runs the opposite way. A redundant
 * re-store is byte-identical and costs one request, so that bar errs short. A redundant rekey makes every
 * member on every version process a new generation, and leaves content encrypted to the superseded keys
 * unreadable to anyone who never held them — so this errs long.
 *
 * The delay costs nothing: a group that reaches this path has had no retrievable keys message for at least
 * the 30-day config TTL, against which a day is noise. Standardised across the clients; do not tune it as
 * though something local depended on it.
 */
private val REKEY_STORM_GUARD_MS = 24.hours.inWholeMilliseconds

/**
 * Puts config messages back on the swarm after they've been swept for exceeding their TTL.
 *
 * A config has a 30 day TTL, refreshed every time we poll. Go quiet for longer than that and your
 * account state is deleted, so restoring from seed gives you an empty account — even though any
 * device still logged in is holding a perfectly good copy. This class uploads that copy again.
 *
 * It is safe to do so for one specific reason, which is worth understanding because it's what makes
 * the whole thing non-destructive: config encryption is deterministic, and the storage server
 * derives a message's hash from its ciphertext alone (no timestamp, no TTL). Re-uploading an
 * unchanged config therefore produces *the same message hash it had before* — it isn't a new message
 * competing with existing state, it's the same message going back where it was. `store` is purely
 * additive, so there is no new seqno, no merge, no fork, and nothing can be overwritten. Two devices
 * recovering at once compute the same hash and the second is a no-op.
 *
 * The variant to never introduce here is "mark the config dirty to force an upload". That bumps the
 * seqno and triggers a merge, which is exactly what this design exists to avoid, and it is what
 * makes a long-offline device destructive rather than helpful.
 *
 * @see org.thoughtcrime.securesms.api.snode.detectMissingConfigHashes for how "missing" is decided.
 * @see ConfigRestoreSource for which configs are eligible.
 */
/** What a keys backfill established. The force rekey acts on [Failed] alone. */
enum class KeysBackfill {
    /** Nothing was asked: every active keys hash already has bytes, or an attempt is barred. */
    NotAttempted,

    /** Some node returned bytes this device lacked. */
    Captured,

    /** Every node of the swarm answered, and none returned bytes for any keys hash this device lacks. */
    Failed,

    /**
     * No node returned the bytes, but not every node answered, or the swarm could not be looked up. A node
     * that did not answer is not evidence the message is gone, so this is not a failure.
     */
    Inconclusive,
}

/**
 * Identifies one poll of one swarm — the unit that separates "level at some point this session" from
 * "level as of the poll I am in".
 *
 * Minted by [ExpiredConfigRecovery.beginPoll] and carried by the caller. Carrying it is what keeps it
 * correct while polls overlap: two concurrent polls hold different tokens, and a single shared "current
 * token" consulted by readers would let either be aged out by the other merely starting.
 *
 * Carrying alone is not sufficient, though — a caller can carry a token long past the poll that minted it.
 * The store therefore also records each swarm's current poll and verifies the token against it. See
 * [ExpiredConfigRecovery.localStateIsLevelWithSwarmAsOf] for why both halves are needed.
 */
@JvmInline
value class PollToken internal constructor(private val id: Long)

@Singleton
class ExpiredConfigRecovery @Inject constructor(
    private val restoreSource: ConfigRestoreSource,
    private val clock: SnodeClock,
    private val appVisibilityManager: AppVisibilityManager,
    private val swarmApiExecutor: SwarmApiExecutor,
    private val storeMessageApiFactory: StoreMessageApi.Factory,
    private val deleteMessageApiFactory: DeleteMessageApi.Factory,
    private val retrieveMessageFactory: RetrieveMessageApi.Factory,
    private val configFactory: ConfigFactoryProtocol,
    private val swarmDirectory: SwarmDirectory,
) {
    /**
     * Swarms our local state is known to be level with — i.e. there is nothing on the swarm we
     * haven't already taken in — each recorded against the poll that established it.
     *
     * That property, not "a poll happened" or "a merge happened", is what makes a re-store safe: a
     * device that has taken in whatever the swarm had re-stores the incorporated result, which is
     * correct by construction. Re-storing while the swarm still holds config we haven't seen is the
     * dangerous ordering, and it's what this guard exists to prevent.
     *
     * One field, two readings, and that is the whole reason the value is a token rather than a flag:
     *
     *  - present at all — level at some point this session and not since withdrawn. What
     *    [localStateIsLevelWithSwarm] asks, and the right question for a re-store, where staleness costs
     *    only a redundant, byte-identical write.
     *  - equal to the caller's token — level as of the poll the caller is in. What
     *    [rekeyIfUnrecoverable] asks, and the only safe question ahead of a write that is irreversible and
     *    encrypts to this device's view of the members.
     *
     * Resist splitting these into two fields. They are the same observation read at two lifetimes, and two
     * fields would let one be updated without the other — which is the state neither reading survives.
     *
     * In memory, deliberately. A persisted mark would answer the first question about a session whose
     * merges we can no longer vouch for, and the withdrawal in [markMergeIncompleteForSwarm] is exactly
     * the kind of negative that must not outlive the run that observed it.
     */
    private val swarmsLevelWithLocalState = ConcurrentHashMap<String, PollToken>()

    private val nextPollToken = AtomicLong()

    /**
     * The most recent poll of each swarm, so a token handed back to us can be checked for being *current*
     * and not merely for naming some poll that once happened.
     *
     * Keyed per swarm, which is what lets the currency check exist without reintroducing the cross-swarm
     * bug: another swarm's poll advances only its own entry, so it can never age ours out.
     */
    private val currentPollTokens = ConcurrentHashMap<String, PollToken>()

    /**
     * Mints a token for a poll of [swarmPubKeyHex] that is starting, and records it as that swarm's current
     * poll.
     *
     * Call once at the top of a poll and hand that same token to [markLocalStateLevelWithSwarm] and
     * [rekeyIfUnrecoverable]. Do not re-mint between them, and do not call it from anywhere that is not a
     * poll actually beginning — calling it advances the swarm's current poll, which retires the level mark
     * any in-flight poll is relying on.
     */
    fun beginPoll(swarmPubKeyHex: String): PollToken =
        PollToken(nextPollToken.incrementAndGet()).also { currentPollTokens[swarmPubKeyHex] = it }

    /**
     * Swarms where a poll this session failed to take in everything it fetched.
     *
     * Sticky for the session, and that is the point. A config message we couldn't merge is not
     * offered to us again: the dedup table marks a hash as seen before the merge is attempted, and the
     * poller's `lastHash` advances on a successful *fetch*, so the swarm won't return it on the next
     * poll either. So the very next poll looks completely clean while local state is still missing what
     * that message carried — and without this, it would mark the swarm level and authorise a re-store.
     *
     * Recording it once and refusing for the rest of the session is the cheap, correct answer: recovery
     * is a best-effort repair, so deferring it to the next app start costs almost nothing, whereas
     * acting on a view we know to be incomplete is the thing the guard exists to prevent.
     */
    private val swarmsWithIncompleteMerge =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Hashes claimed by a recovery round, so that a swarm reporting the same hash missing on every poll
     * costs one store rather than one per poll.
     *
     * Note this is not "at most one attempt per hash per session", and anything reasoning from that
     * stronger claim will be wrong. The bar is on a store that succeeded: a round that *fails*
     * releases its claims so a later poll can retry (see [runRestore]), because otherwise the storm guard
     * would be the thing making a partial upload permanent — and worse, a single transient network failure
     * would cost a device its repair for the whole session. What bounds the retrying is
     * [RECOVERY_RETRY_BACKOFF_MS], not this set.
     *
     * This set answers exactly one question — *"is there any point acting on this hash again?"* — and it
     * is written by two different causes that happen to share that answer: a store that succeeded, and a
     * hash a guard ruled out. It is therefore not a record of what was restored, and a future consumer
     * asking that (a metric, a UI, a "did recovery help?" check) must not read it. If you need to
     * distinguish them, add a second set rather than reinterpreting this one: they are answers to different
     * questions that currently coincide, and one value cannot be wrong about one without being wrong about
     * the other while reading correctly at both call sites.
     */
    private val attemptedHashes = ConcurrentHashMap<String, Long>()

    /** Hashes still barred — i.e. claimed within [RESTORED_HASH_BAR_MS]. */
    private fun currentlyBarred(): Set<String> {
        val now = clock.currentTimeMillis()
        // Prune as we go: an expired entry is indistinguishable from an absent one, and letting the map
        // grow for the life of the process would be a slow leak on a long-lived session — the very case
        // this bar was made time-bounded for.
        attemptedHashes.entries.removeAll { now - it.value >= RESTORED_HASH_BAR_MS }
        return attemptedHashes.keys
    }

    /** Consecutive failed rounds per swarm, and when the last one was — see [RECOVERY_RETRY_BACKOFF_MS]. */
    private val backoffState = ConcurrentHashMap<String, BackoffState>()

    private class BackoffState(val failedAt: Long, val consecutiveFailures: Int) {
        /** Doubles per consecutive failure, capped. Bounds the wait, not the attempt count. */
        val waitMs: Long
            get() = minOf(
                RECOVERY_RETRY_BACKOFF_CEILING_MS,
                RECOVERY_RETRY_BACKOFF_MS shl (consecutiveFailures - 1).coerceAtMost(20),
            )
    }

    /**
     * Records that local state is level with [swarmPubKeyHex] — call this only after a poll that
     * succeeded, and only once whatever config it returned has been taken in.
     *
     * Two ways to get this wrong, both of which have bitten a Session client:
     *
     * Requiring a merge before re-storing reads as the careful choice and is the one thing that would
     * quietly make this whole feature a no-op for the devices it exists for: a device whose configs have
     * expired gets *nothing* back when it polls, so there is nothing to merge, and it would never
     * recover — while every device whose configs were fine would. An empty poll establishes the property
     * we need directly: nothing is on the swarm that we haven't already taken in. Hence
     * [mergedConfigMessagesForDiagnosticsOnly] is logged and must not affect the outcome.
     *
     * A failed poll is the case that must not count — it says nothing about swarm state, so treating
     * it as level reintroduces the same hazard from the other side. On this client that's structural
     * rather than checked here: a failed retrieve throws (see `AutoRetryApiExecutor`, which rethrows once
     * retries are exhausted), so callers never reach this line. Empty and failed are different *types*
     * here, not the same value — which is the trap on clients where both arrive as an empty array.
     *
     * So: do not add a condition here, and do not call this from a path that can be reached on failure.
     *
     * @param mergedConfigMessagesForDiagnosticsOnly Logged, never acted on. The clumsy name is
     *  deliberate, because the obvious reading of a shorter one is that it should influence the decision
     *  — which is the bug. Deleting this parameter removes the only mechanism by which anyone can
     *  demonstrate that this guard works: without it, "polled but merged nothing" cannot be expressed
     *  in a test, so the test for it collapses into a duplicate of the happy path and can no longer fail.
     *  A guard whose test cannot fail is precisely the defect this parameter exists to make impossible.
     *  Remove it as a decision, not as a tidy-up.
     */
    /**
     * Records the outcome of a poll's config merge: level with [swarmPubKeyHex] if it took in everything it
     * fetched, withdrawn from recovery if it did not. The one place a poller decides between the two.
     */
    fun recordConfigMerge(
        swarmPubKeyHex: String,
        pollToken: PollToken,
        tookEverythingIn: Boolean,
        mergedConfigMessagesForDiagnosticsOnly: Boolean,
    ) {
        if (tookEverythingIn) {
            markLocalStateLevelWithSwarm(swarmPubKeyHex, pollToken, mergedConfigMessagesForDiagnosticsOnly)
        } else {
            markMergeIncompleteForSwarm(swarmPubKeyHex)
        }
    }

    fun markLocalStateLevelWithSwarm(
        swarmPubKeyHex: String,
        pollToken: PollToken,
        mergedConfigMessagesForDiagnosticsOnly: Boolean,
    ) {
        // An earlier poll this session already lost something we can never be offered again, so a clean
        // poll now doesn't mean what it looks like it means. See [swarmsWithIncompleteMerge].
        if (swarmPubKeyHex in swarmsWithIncompleteMerge) {
            return
        }

        Log.d(
            TAG,
            "Local state is level with the swarm " +
                    "(merged config: $mergedConfigMessagesForDiagnosticsOnly)"
        )
        swarmsLevelWithLocalState[swarmPubKeyHex] = pollToken
    }

    /**
     * Records that a poll of [swarmPubKeyHex] did not take in everything it fetched — a merge that
     * threw, or one that skipped a message it couldn't parse and returned normally.
     *
     * This is not merely the absence of [markLocalStateLevelWithSwarm]: it withdraws the swarm for the rest
     * of the session, because the message we missed will not come back. See [swarmsWithIncompleteMerge].
     */
    fun markMergeIncompleteForSwarm(swarmPubKeyHex: String) {
        Log.w(TAG, "A poll didn't take in everything it fetched; no recovery this session")
        swarmsWithIncompleteMerge.add(swarmPubKeyHex)
        swarmsLevelWithLocalState.remove(swarmPubKeyHex)
    }

    /**
     * Whether local state is known to be level with [swarmPubKeyHex] this session — the
     * precondition for recovery, and the only thing that may authorise a re-store.
     */
    // `internal` for one reason: the test that keeps the two readings from collapsing into each other has
    // to observe BOTH at the same instant. Asserting only that the rekey refused cannot tell "the mark is
    // present but belongs to an earlier poll" — the case that must refuse — from "the mark is gone", which
    // also refuses and would equally have broken the re-store. Split across two tests they can drift.
    internal fun localStateIsLevelWithSwarm(swarmPubKeyHex: String): Boolean =
        swarmsLevelWithLocalState.containsKey(swarmPubKeyHex)

    /**
     * Whether local state is level with [swarmPubKeyHex] **as of the poll [pollToken] names, and that poll
     * is still the current one** — the stricter reading of the mark, and the only one that may authorise a
     * rekey.
     *
     * BOTH conjuncts are load-bearing, and the second is the one that is easy to lose. Checking only
     * that the mark equals the token asks "was the mark made by the poll you are naming" — which a caller
     * holding an OLD token satisfies, because the mark that poll left is still sitting in the map. It would
     * answer "you are level now" to a caller whose information is arbitrarily old, and it would do so
     * without failing, which is the fail-open shape this guard exists to rule out. A token names a poll; it
     * does not make that poll current, and nothing about holding one makes it fresh.
     *
     * This is a carry-and-verify design: the caller threads its token through, and we additionally require
     * it to be the swarm's current poll. The alternative — taking no token and reading both sides from our
     * own state — is immune to a bad token by construction, but answers "the most recent poll was level"
     * to a caller running outside any poll at all, because nothing here marks a poll as finished. Since the
     * realistic way this guard ever becomes load-bearing is the rekey's call site moving out of the poll
     * body, the shape that fails closed for that caller is the one worth having.
     *
     * The residual, which neither shape closes: the token is ordinal, not temporal. If no poll has begun
     * since the one that marked us, its token is still current however long ago it ran.
     *
     * Named rather than inlined so the two readings of [swarmsLevelWithLocalState] sit together and neither
     * can be changed without the other being seen.
     */
    private fun localStateIsLevelWithSwarmAsOf(swarmPubKeyHex: String, pollToken: PollToken): Boolean =
        currentPollTokens[swarmPubKeyHex] == pollToken &&
                swarmsLevelWithLocalState[swarmPubKeyHex] == pollToken

    /**
     * Acts on an expiry check for the current user's own configs.
     *
     * [auth] is the auth the poll itself used, so recovery signs as whoever noticed the problem.
     */
    suspend fun onUserConfigsChecked(auth: SwarmAuth, report: ConfigExpiryReport) {
        recover(auth, report, restoreSource::userConfigsToRestore)
    }

    /** Acts on an expiry check for a group's configs. */
    /**
     * Groups whose keys this device has just successfully put back on the swarm.
     *
     * The expired-group banner is otherwise cleared reactively, when a keys message is *handled* — and the
     * device that did the re-storing already holds that hash, so it may never handle it again. Relying on
     * the reactive path alone would leave the banner up forever over keys that are back on the swarm, which
     * is the one case it must not do.
     *
     * `replay = 1` deliberately. The collector is started eagerly at process start, so in practice nothing
     * can be emitted before it subscribes — recovery only runs from a poll. But a dropped emission here
     * fails silently and permanently, while a replayed one merely clears a flag that is already clear, so
     * the asymmetry decides it rather than an argument about reachability.
     */
    val keysRestored: SharedFlow<AccountId> get() = mutableKeysRestored.asSharedFlow()

    private val mutableKeysRestored = MutableSharedFlow<AccountId>(replay = 1)

    /**
     * Whether the group's keys are within this device's reach, for the expired-group flag. Delegated so the
     * poller has one recovery-side collaborator, and so the flag and the re-store share a single predicate.
     */
    fun canRepairGroupKeys(groupId: AccountId, missingHashes: Set<String>): Boolean =
        restoreSource.canRepairGroupKeys(groupId, missingHashes)

    /**
     * @return the keys-repair verdict this round settled, which the caller applies to the expired flag:
     *  `true` the keys landed, `false` a keys re-store was attempted and failed, `null` no keys re-store
     *  happened at all — the guards declined, or the keys were not among the missing hashes.
     *
     *  Detection deliberately defers the flag when this device holds the bytes, because holding them is not
     *  the same as having put them back. Null therefore means "still no verdict", and the flag must be left
     *  exactly as it was rather than cleared: an unattempted repair is not a completed one.
     */
    suspend fun onGroupConfigsChecked(
        groupId: AccountId,
        auth: SwarmAuth,
        report: ConfigExpiryReport,
    ): Boolean? {
        var keysVerdict: Boolean? = null

        recover(
            auth = auth,
            report = report,
            gather = { missing -> restoreSource.groupConfigsToRestore(groupId, missing) },
            // Per SUCCEEDED restore, not per round: a round that stored info and members but failed on keys
            // must leave the banner up. Emitting from the round would make that case pass for the wrong
            // reason.
            // Per restore, not per round: a round that stored info and members but failed on keys must
            // leave the banner up, and a round-level signal cannot tell those apart.
            onAttempted = { restore, landed ->
                if (restore.isGroupKeys) {
                    keysVerdict = landed
                    if (landed) {
                        Log.i(TAG, "Group keys restored for $groupId; clearing any expired flag")
                        mutableKeysRestored.tryEmit(groupId)
                    } else {
                        Log.w(TAG, "Group keys re-store FAILED for $groupId; the group stays flagged")
                    }
                }
            },
        )

        return keysVerdict
    }

    private suspend fun recover(
        auth: SwarmAuth,
        report: ConfigExpiryReport,
        gather: (missingHashes: Set<String>) -> List<PendingRestore>,
        onAttempted: (restore: PendingRestore, landed: Boolean) -> Unit = { _, _ -> },
    ) {
        val missing = (report as? ConfigExpiryReport.Checked)?.missingHashes.orEmpty()
        if (missing.isEmpty()) {
            return
        }

        // Detection runs wherever polling runs, including in the background. Acting on it doesn't:
        // recovery is N extra store requests, and the largest N is the long-offline case, which is
        // also the one most likely to be running in a constrained background window. None of this is
        // latency sensitive, so it can wait for the app to be open.
        if (!appVisibilityManager.isAppVisible.value) {
            Log.d(TAG, "Not recovering ${missing.size} config message(s) while in the background")
            return
        }

        if (!localStateIsLevelWithSwarm(auth.accountId.hexString)) {
            Log.d(TAG, "Not recovering config messages until local state is level with this swarm")
            return
        }

        // Back off after a failed round rather than counting attempts. Claiming hashes on attempt is what
        // stops a swarm reporting the same hash missing on every poll costing a store every poll — but on
        // its own it also banks a failure as if it had succeeded, so a half-uploaded config would stay
        // half-uploaded for the session on the very device the feature exists to repair. Failed rounds
        // therefore release their claims (see [runRestore]), and this is what keeps that from becoming the
        // storm the claiming prevented.
        //
        // Deliberately a rate limit and not a cap: a limit on attempts would exclude a device whose store
        // keeps failing, which is the same population being repaired.
        backoffState[auth.accountId.hexString]?.let { state ->
            val sinceFailure = clock.currentTimeMillis() - state.failedAt
            if (sinceFailure < state.waitMs) {
                Log.d(TAG, "Not recovering: backing off for ${state.waitMs - sinceFailure}ms")
                return
            }
        }

        val fresh = missing - currentlyBarred()
        if (fresh.isEmpty()) {
            return
        }

        // An inspection that THREW is not a guard verdict, so nothing is barred and no backoff is consumed
        // — the hashes stay retryable. Catching it here also keeps recovery from breaking the poll it rides
        // on: `gather` runs libsession code (`push()` throws when a config has no keys), and the handoff in
        // Poller.poll() sits at the end of the poll with nothing above it to catch this.
        val restores = try {
            gather(fresh)
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Log.w(TAG, "Could not inspect configs for recovery; leaving these hashes retryable", e)
            return
        }

        // "Not stored" is three cases, not two. A store that FAILED is retryable — that's the whole point
        // of releasing claims below. But a hash a *guard* ruled out is barred like a success, because
        // nothing about it changes on the timescale the bar covers: it isn't current, or its config is
        // dirty and about to be pushed under a new hash anyway, or the group is gone, or it's a keys hash
        // this device holds no retained bytes for — which is a fact about what we loaded, so it will not
        // become true by asking again soon.
        //
        // Barred through the same expiring map as a successful store, deliberately: over hours a kicked
        // group can be rejoined, a destroyed one replaced, a dirty config settle. "Nothing will change"
        // was only ever true for a burst of polls, and re-examining a guard costs no network call — so
        // there is nothing to buy by making this permanent.
        //
        // Folding these into "failure" costs no requests, which is exactly why it doesn't look like a
        // problem — but it re-runs the gather on every poll, which takes the config write lock and re-logs
        // the same rejection every few seconds for the rest of the session.
        val guardRejected = fresh - restores.flatMapTo(mutableSetOf()) { it.claimedHashes }
        if (guardRejected.isNotEmpty()) {
            Log.d(TAG, "${guardRejected.size} hash(es) ruled out by a guard; not revisiting for now")
            guardRejected.forEach { attemptedHashes[it] = clock.currentTimeMillis() }
        }

        if (restores.isEmpty()) {
            return
        }

        // Claim the hashes before doing any work, so that concurrent polls of the same swarm — and
        // every subsequent poll this session — leave them alone.
        val claimedAt = clock.currentTimeMillis()
        restores.forEach { r -> r.claimedHashes.forEach { attemptedHashes[it] = claimedAt } }

        Log.i(TAG, "Recovering expired configs: ${restores.joinToString { it.label }}")

        val outcomes = runRound(auth, restores)

        // Per restore, index-aligned with [restores], and reporting FAILURES as well as successes —
        // a caller that only hears about successes cannot tell "failed" from "never attempted", and those
        // require opposite treatment of the expired flag.
        outcomes.forEachIndexed { index, landed -> onAttempted(restores[index], landed) }

        // Decided once for the whole round rather than per config, so a mixed round can't race itself into
        // an arbitrary state depending on which config finished last.
        val swarm = auth.accountId.hexString
        if (outcomes.any { it }) {
            // Something landed, so the swarm is reachable and whatever failed deserves a prompt retry.
            backoffState.remove(swarm)
        } else {
            val consecutive = (backoffState[swarm]?.consecutiveFailures ?: 0) + 1
            backoffState[swarm] = BackoffState(clock.currentTimeMillis(), consecutive)
            Log.d(TAG, "Recovery round failed ($consecutive consecutive); backing off")
        }
    }

    /**
     * Runs one round's stores and deletes, chunked so no batch can exceed the server's sub-request limit.
     *
     * @return per-restore success, index-aligned with [restores]. A restore succeeded only if *every* one of
     *  its messages stored: a multipart config with one bad part is not stored at all.
     */
    private suspend fun runRound(auth: SwarmAuth, restores: List<PendingRestore>): List<Boolean> {
        val succeeded = MutableList(restores.size) { true }

        val stores = restores.flatMapIndexed { index, restore ->
            restore.push.messages.map { message -> Op.Store(index, restore.namespace, message.data) }
        }

        // Sequential chunks, concurrent within a chunk. Awaiting a chunk fully means its batch has already
        // round-tripped, so the next chunk cannot join it — that is what makes the boundary real rather
        // than a hope about timing.
        //
        // One config's parts SPAN chunks freely, and must: at ~66 parts a large config needs four of them.
        // What counts as *stored* is every part of the config — tracked per restore below — not which
        // transport the parts travel in. Skipping an over-sized config instead would make anything past
        // ~1.5MB permanently unrecoverable, which is the largest-accounts population the feature is for.
        runChunked(auth, stores) { op, failure ->
            // One chunk failing must not bar another restore's hashes, so failures are recorded against
            // the owning restore rather than the round.
            succeeded[op.restoreIndex] = false
            Log.w(TAG, "Failed to store ${restores[op.restoreIndex].label}", failure)
        }

        // Deletes are built only AFTER the stores are in, and only for configs that fully landed.
        //
        // `push()` has already cleared the obsolete hashes, so this is the only chance to delete them — and
        // deleting them when the replacement store *failed* removes the swarm's older copy without adding
        // the new one, leaving a seed restore in that window with nothing rather than stale state.
        //
        // Skipping them instead leaks nothing in the case that matters, and the argument is a timeline
        // rather than a probability. Obsolete hashes are never TTL-extended — the extend set comes from
        // `activeHashes()`, which is `_curr_hashes` plus pending multiparts and excludes `_old_hashes`. So
        // with `T_old < T_cur` (a hash becomes obsolete when its successor is created) and `T_cur <= now-30d`
        // (we are here because the current hash genuinely expired), `T_old + 30d < now`: **the obsolete hash
        // expired first, necessarily.** The delete is a no-op precisely where recovery is legitimate.
        //
        // Where it is *not* a no-op is a false positive — one out-of-sync snode is enough to trigger a round
        // (D1), so the obsolete hash may be recent and still live. That is the case where deleting it does
        // real harm. Leaking something already gone and deleting something still there are not symmetric.
        val deletes = restores.mapIndexedNotNull { index, restore ->
            restore.push.obsoleteHashes
                .takeIf { it.isNotEmpty() && succeeded[index] }
                ?.let { Op.Delete(index, it) }
        }

        runChunked(auth, deletes) { op, failure ->
            // Not load bearing: the superseded messages expire on their own TTL regardless.
            Log.w(TAG, "Failed to delete obsolete hashes for ${restores[op.restoreIndex].label}", failure)
        }

        succeeded.forEachIndexed { index, ok ->
            if (ok) {
                Log.i(TAG, "Recovered ${restores[index].label}")
            } else {
                // Release the claims so a later round can retry. The bar applies to a hash whose store SUCCEEDED,
                // not one that was merely attempted — and "succeeded" means every message's own
                // sub-response was 2xx, since each is a separate execute() that throws on its own non-2xx.
                // A multipart config with one bad part therefore stays retryable in full: a
                // half-uploaded config can't be reconstructed, so banking it would leave it broken.
                attemptedHashes.keys.removeAll(restores[index].claimedHashes)
            }
        }

        return succeeded
    }

    /** Runs [ops] in chunks no larger than the server's sub-request limit, reporting each failure. */
    private suspend fun <T : Op> runChunked(
        auth: SwarmAuth,
        ops: List<T>,
        onFailure: (T, Throwable) -> Unit,
    ) {
        for (chunk in ops.chunked(MAX_BATCH_SUB_REQUESTS)) {
            val outcomes = coroutineScope {
                chunk.map { op -> async { runCatching { execute(auth, op) } } }.awaitAll()
            }

            outcomes.forEachIndexed { position, outcome ->
                val failure = outcome.exceptionOrNull() ?: return@forEachIndexed
                if (failure is CancellationException) throw failure

                onFailure(chunk[position], failure)
            }
        }
    }

    private suspend fun execute(auth: SwarmAuth, op: Op) {
        retryWithUniformInterval {
            when (op) {
                is Op.Store -> swarmApiExecutor.execute(
                    SwarmApiRequest(
                        swarmPubKeyHex = auth.accountId.hexString,
                        api = storeMessageApiFactory.create(
                            namespace = op.namespace,
                            message = SnodeMessage(
                                auth.accountId.hexString,
                                Base64.encodeBytes(op.data),
                                SnodeMessage.CONFIG_TTL,
                                clock.currentTimeMillis(),
                            ),
                            auth = auth,
                        )
                    )
                )

                // push() hands back — and clears — libsession's obsolete hash set even for a clean config,
                // so dropping these would lose messages a later real push would have deleted, permanently:
                // the next push() returns an empty list. Issuing the delete keeps this behaviourally
                // identical to ConfigUploader.pushConfig, minus the confirmPushed (nothing to confirm — the
                // seqno never moved).
                is Op.Delete -> swarmApiExecutor.execute(
                    SwarmApiRequest(
                        swarmPubKeyHex = auth.accountId.hexString,
                        api = deleteMessageApiFactory.create(
                            swarmAuth = auth,
                            messageHashes = op.hashes,
                        )
                    )
                )
            }
        }
    }

    /** One sub-request, tagged with the restore it belongs to so a failure can be attributed. */
    private sealed interface Op {
        val restoreIndex: Int

        class Store(override val restoreIndex: Int, val namespace: Int, val data: ByteArray) : Op
        class Delete(override val restoreIndex: Int, val hashes: List<String>) : Op
    }

    // ── keys backfill ────────────────────────────────────────────────────────────────────────────────
    //
    // Re-loads a group's keys messages so libsession captures their BYTES. Retention records those bytes
    // when a message is loaded, so a group that loaded its keys before retention existed holds the keys and
    // the hashes and no bytes — and bytes are what a re-store needs. Feeding the same messages back through
    // the ordinary merge fixes it: libsession takes the "we already have this key" early return, a no-op for
    // key state that still records the bytes and flags a dump.
    //
    // It runs proactively rather than when a config is found missing, because the two conditions are
    // opposites: a re-store is needed when the swarm has LOST a hash, this when WE lack bytes for one the
    // swarm still HAS. By the time the first is true, the message this needs is gone.

    /**
     * Resolved lazily, and injectable for tests, because libsession's [Namespace] is a native class:
     * touching it runs an initialiser that loads the shared library, which no JVM unit test in this project
     * can do. Calling it inline would make every test of this component fail with NoClassDefFoundError
     * regardless of what it was asserting — the same reason [PendingRestore] keeps its namespace a lambda.
     */
    internal var keysNamespace: () -> Int = { Namespace.GROUP_KEYS() }
    /**
     * Groups whose namespace we have re-polled recently, so a group whose keys are genuinely gone does not
     * re-poll on every poll for the rest of the session.
     *
     * Deliberately the same interval as the re-store bar rather than a new one: the trade is identical and
     * lands further on this side, since the redundant action here is a small *read* rather than a write.
     *
     * This answers HOW OFTEN TO RETRY. It does not answer WHETHER A REKEY MAY FIRE. Those are two
     * questions and they want opposite treatment of an expiry: letting the bar lapse simply permits another
     * cheap read, whereas treating a lapsed entry as "a repair was attempted" would license an irreversible,
     * every-member-visible write on evidence this object has already discarded. The force rekey below must
     * not read this map as its precondition, and above all this must not be made persistent to serve it. A persisted attempt record is a sticky negative: it would let a rekey
     * fire on evidence gathered weeks ago, after the swarm has changed. In-memory fails CLOSED — a rekey is
     * delayed by one poll cycle at worst, never blocked, because this runs inside the poll.
     */
    private val attemptedAt = ConcurrentHashMap<String, Long>()

    /**
     * Re-fetches the keys namespace until the bytes this device lacks are captured, asking the poll's own
     * node first and then every other node of the group's swarm.
     *
     * Asking only one node would make its answer the evidence for the force rekey, which is irreversible:
     * one node that has lost a message, or never had it, is not the swarm losing it. A copy anywhere in
     * the swarm is merged here and put back by the ordinary re-store, rather than replaced by a rekey.
     *
     * @return what the attempt established. [KeysBackfill.Failed] is the only outcome the force rekey
     *  acts on, so it is reserved for the case where every node answered.
     */
    suspend fun backfillIfNeeded(groupId: AccountId, auth: SwarmAuth, snode: Snode): KeysBackfill {
        val lacking = keysHashesWithoutBytes(groupId)
        if (lacking.isEmpty()) {
            // Nothing to do, and nothing to record: the condition is self-clearing, so a group that has
            // already been backfilled simply stops qualifying. No migration flag, no one-shot bit.
            return KeysBackfill.NotAttempted
        }

        val now = clock.currentTimeMillis()
        attemptedAt.entries.removeAll { now - it.value >= RESTORED_HASH_BAR_MS }
        if (attemptedAt.putIfAbsent(groupId.hexString, now) != null) {
            return KeysBackfill.NotAttempted
        }

        Log.i(TAG, "Backfilling keys bytes for $groupId")

        var someNodeDidNotAnswer = false

        /** @return whether this device now holds bytes for every active keys hash. */
        suspend fun ask(node: Snode): Boolean {
            val messages = try {
                fetchKeysMessages(groupId, auth, node)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Keys backfill: no answer from $node for $groupId", e)
                someNodeDidNotAnswer = true
                return false
            }

            if (messages.isNotEmpty()) {
                // The ordinary merge path. Every one of these is a message we already hold the key for, so
                // this is a no-op for key state by design: the point is the retention it performs on the way
                // through, and the dump it flags.
                configFactory.mergeGroupConfigMessages(
                    groupId = groupId,
                    keys = messages.map { ConfigMessage(it.hash, it.data, it.timestamp.toEpochMilli()) },
                    info = emptyList(),
                    members = emptyList(),
                )
            }

            return keysHashesWithoutBytes(groupId).isEmpty()
        }

        if (!ask(snode)) {
            // Fetched rather than read from the cache. The cached swarm is trimmed as nodes fail and is only
            // refreshed when a node reports it has left, so it can be both out of date and short, and
            // "every node answered" has to mean every node of the swarm as it is now.
            val swarm = try {
                swarmDirectory.fetchSwarmCounted(groupId.hexString)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Keys backfill: could not look up the swarm for $groupId", e)
                null
            }

            // An entry in the swarm listing that could not be read is a node that is never asked, so it counts
            // as one that did not answer.
            if (swarm == null || swarm.nodes.isEmpty() || swarm.unreadable > 0) {
                someNodeDidNotAnswer = true
            }
            if (swarm != null) {
                for (node in swarm.nodes) {
                    if (node.ed25519Key == snode.ed25519Key) continue
                    if (ask(node)) break
                }
            }
        }

        val captured = lacking - keysHashesWithoutBytes(groupId)
        return when {
            captured.isNotEmpty() -> KeysBackfill.Captured
            someNodeDidNotAnswer -> {
                Log.w(TAG, "Keys backfill for $groupId is inconclusive: not every node answered")
                KeysBackfill.Inconclusive
            }
            else -> {
                Log.w(TAG, "No node in the swarm holds the keys messages $groupId lacks bytes for")
                KeysBackfill.Failed
            }
        }
    }

    private suspend fun fetchKeysMessages(groupId: AccountId, auth: SwarmAuth, node: Snode) =
        swarmApiExecutor.execute(
            SwarmApiRequest(
                swarmPubKeyHex = groupId.hexString,
                // Pinned: with an override the executor retries on this node only, so a failure is this
                // node's and an answer is this node's.
                swarmNodeOverride = node,
                // No lastHash on purpose: we want everything the node still holds for this namespace, not
                // the messages since our cursor. The messages we need are ones we already consumed, so a
                // cursored fetch returns exactly nothing.
                api = retrieveMessageFactory.create(
                    lastHash = "",
                    auth = auth,
                    namespace = keysNamespace(),
                    maxSize = null,
                ),
            )
        ).messages

    /** The group's active keys hashes that have no bytes behind them. */
    private fun keysHashesWithoutBytes(groupId: AccountId): Set<String> =
        configFactory.withGroupConfigs(groupId) { configs ->
            val held = configs.groupKeys.activeKeyMessages().keys
            configs.groupKeys.activeHashes().filterTo(mutableSetOf()) { it !in held }
        }

    // ── force rekey ──────────────────────────────────────────────────────────────────────────────────
    //
    // Last resort for a group whose keys are gone from the swarm and whose bytes no device here holds: mint
    // a new generation so the group can carry on, accepting that content encrypted to the superseded keys
    // stays unreadable.
    //
    // The backfill above must not read this section's state or call into it. It repairs the ordinary case
    // and has to keep working, and keep being testable, independently of the one irreversible write here.

    private val lastRekeyAt = ConcurrentHashMap<String, Long>()

    /**
     * @param backfill what this poll's backfill established. Only [KeysBackfill.Failed] permits a rekey:
     *  every node of the swarm answered and none had the bytes. Whether the bytes are absent now is not taken
     *  from it; that is read here, from the state as it stands after the backfill.
     * @param report this poll's expiry check, with [keysHashes] the keys hashes it asked about. A rekey needs
     *  it to say every one of them is gone from the swarm.
     * @param pollToken the token for the poll this call is part of, from [beginPoll]. The members view
     *  must be level as of this poll — not merely at some point this session.
     * @return true if a rekey was actually issued — the only outcome worth asserting on, since every guard
     *  below produces the same visible result as doing nothing.
     *
     * Never throws. This runs inside the poll, and the poll rethrows whatever escapes it once it finishes,
     * which would fail a poll whose merge and re-stores had already succeeded.
     */
    fun rekeyIfUnrecoverable(
        groupId: AccountId,
        backfill: KeysBackfill,
        report: ConfigExpiryReport?,
        keysHashes: Set<String>,
        pollToken: PollToken,
    ): Boolean = try {
        rekeyIfUnrecoverableOrThrow(groupId, backfill, report, keysHashes, pollToken)
    } catch (e: Exception) {
        Log.e(TAG, "Force rekey of $groupId failed", e)
        false
    }

    private fun rekeyIfUnrecoverableOrThrow(
        groupId: AccountId,
        backfill: KeysBackfill,
        report: ConfigExpiryReport?,
        keysHashes: Set<String>,
        pollToken: PollToken,
    ): Boolean {
        if (backfill != KeysBackfill.Failed) return false

        if (!everyKeysHashMissing(report, keysHashes)) {
            Log.d(TAG, "Not rekeying $groupId: the expiry check does not say every keys hash is gone")
            return false
        }

        val group = configFactory.getGroup(groupId)
        if (group == null || group.kicked || group.destroyed) return false

        // Admin-only, and a member must not even appear to try: a member has no signing key, so a rekey
        // would fail at the point of signing rather than be rejected here, which reads as an error rather
        // than as a thing that was never applicable.
        if (group.adminKey == null) {
            Log.d(TAG, "Not rekeying $groupId: this device is not an admin")
            return false
        }

        // A device holding any retained keys message can put the keys back itself, so a rekey is not the
        // remedy for it, however the last re-store went. Read here rather than inferred from the poll's
        // expired verdict, which a failed re-store also raises, and a re-store only runs when the bytes are
        // held. One transient store failure must not be enough to issue an irreversible write.
        val holdsKeysBytes = configFactory.withGroupConfigs(groupId) {
            it.groupKeys.activeKeyMessages().isNotEmpty()
        }
        if (holdsKeysBytes) {
            Log.d(TAG, "Not rekeying $groupId: this device holds keys bytes it can re-store")
            return false
        }

        // A rekey encrypts the new key to THIS DEVICE'S view of the members config. This path fires
        // precisely on devices whose config state is known to be degraded, so a member added while we were
        // away — and not yet merged here — would be silently dropped from the group by a rekey issued from
        // that stale view.
        //
        // Hence EQUALITY with the caller's token, not mere presence in the map. Presence is what
        // [localStateIsLevelWithSwarm] asks for a re-store, and it FAILS OPEN at exactly the wrong moment
        // here: it means "was level at some point this session and has not since been withdrawn", so a
        // member added an hour ago, with our last complete poll yesterday, satisfies it — and that stale
        // delta is precisely what produces the exclusion. Equality demands the mark was laid down by the
        // poll we are in.
        //
        // A predicate's usable lifetime is a property of what you are about to do with it, not of the
        // predicate. Do not "simplify" this to the sticky reading because it reads the same field.
        if (!localStateIsLevelWithSwarmAsOf(groupId.hexString, pollToken)) {
            Log.d(TAG, "Not rekeying $groupId: members view is not level as of this poll")
            return false
        }

        val now = clock.currentTimeMillis()
        lastRekeyAt.entries.removeAll { now - it.value >= REKEY_STORM_GUARD_MS }
        if (lastRekeyAt.putIfAbsent(groupId.hexString, now) != null) {
            Log.d(TAG, "Not rekeying $groupId again within the guard interval")
            return false
        }

        // The guard is taken before the rekey, so a rekey that throws is not retried until the interval has
        // passed. That is the direction to fail in for a write this expensive to repeat.
        Log.w(TAG, "Force-rekeying $groupId: its keys are gone from the swarm and nobody here holds the bytes")
        configFactory.withMutableGroupConfigs(groupId) { configs -> configs.rekey() }
        return true
    }
}

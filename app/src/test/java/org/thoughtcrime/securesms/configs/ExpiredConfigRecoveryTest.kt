package org.thoughtcrime.securesms.configs

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.ConfigPush
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.snode.model.StoreMessageResponse
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.api.snode.ConfigExpiryReport
import org.thoughtcrime.securesms.api.snode.DeleteMessageApi
import org.thoughtcrime.securesms.api.snode.StoreMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.MockLoggingRule
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session-scoped guards around the recovery action (V10 and V13 and their variants).
 *
 * The other cases live where the logic they exercise does: V1-V9 in
 * [org.thoughtcrime.securesms.api.snode.ConfigExpiryDetectionTest], and V11/V12 in
 * [ConfigRestoreSourceTest], which owns the per-config eligibility rules.
 */
class ExpiredConfigRecoveryTest {
    @get:Rule
    val loggingRule = MockLoggingRule()

    private val h2 = "hash-two"
    private val userId = AccountId(IdPrefix.STANDARD, ByteArray(32) { 1 })
    private val groupId = AccountId(IdPrefix.GROUP, ByteArray(32) { 2 })

    private lateinit var restoreSource: ConfigRestoreSource
    private lateinit var appVisibilityManager: AppVisibilityManager
    private lateinit var swarmApiExecutor: SwarmApiExecutor
    private lateinit var deleteMessageApiFactory: DeleteMessageApi.Factory
    private lateinit var recovery: ExpiredConfigRecovery

    /** Store requests issued, so [assertRecoveryStillReachable] can assert an *increase*. */
    private val storeCalls = AtomicInteger(0)

    /** Mutable so a test can background the app and then restore it for the positive control. */
    private val appVisible = MutableStateFlow(true)

    /** Controllable so the retry backoff can be advanced past. */
    private var now = 0L
    private lateinit var clock: SnodeClock

    @Before
    fun setUp() {
        restoreSource = mockk()
        appVisibilityManager = mockk()
        swarmApiExecutor = mockk()
        deleteMessageApiFactory = mockk(relaxed = true)

        storeCalls.set(0)
        appVisible.value = true
        now = 0L
        clock = mockk<SnodeClock>()
        every { clock.currentTimeMillis() } answers { now }
        every { appVisibilityManager.isAppVisible } returns appVisible
        coEvery { swarmApiExecutor.send(any(), any()) } answers {
            when (secondArg<SwarmApiRequest<*>>().api) {
                is DeleteMessageApi -> DeleteMessageApi.SuccessResponse(1, 1)
                else -> {
                    storeCalls.incrementAndGet()
                    StoreMessageResponse(hash = h2, timestamp = Instant.EPOCH)
                }
            }
        }

        givenRestorable(claimedHashes = setOf(h2), messageCount = 1)

        recovery = ExpiredConfigRecovery(
            restoreSource = restoreSource,
            clock = clock,
            appVisibilityManager = appVisibilityManager,
            swarmApiExecutor = swarmApiExecutor,
            storeMessageApiFactory = mockk<StoreMessageApi.Factory>(relaxed = true),
            deleteMessageApiFactory = deleteMessageApiFactory,
        )
    }

    @Test
    fun `a missing hash on a merged swarm is put back`() = runTest {
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(1)
    }

    @Test
    fun `V10 - recovery must not run before any successful poll of the swarm`() = runTest {
        // No successful poll at all — which is a different thing from "polled but merged nothing", the
        // case V22 covers. Detection still happened; we just must not act on it, because re-storing
        // while the swarm holds config we haven't taken in can put back state that has since been
        // deliberately changed.
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(0)
        assertRecoveryStillReachable()
    }

    @Test
    fun `V10b - a successful poll of a different swarm does not unlock this one`() = runTest {
        recovery.markLocalStateLevelWithSwarm(
            AccountId(IdPrefix.GROUP, ByteArray(32) { 9 }).hexString,
            mergedConfigMessagesForDiagnosticsOnly = true,
        )

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(0)
        assertRecoveryStillReachable()
    }

    /**
     * V22 — a successful poll that returned **no config messages** must still permit recovery, and this
     * is the most consequential assertion in the file.
     *
     * The intuitive reading of the guard is "wait until a merge has happened". That reading makes the
     * whole feature a no-op for precisely the devices it was built for: a device whose configs have
     * expired gets nothing back when it polls, so there is nothing to merge, so it would never recover —
     * while every device whose configs were fine would. And it fails *silently*: detection runs, the
     * guard declines, nothing happens, no error is raised, and a suite that hands the recovery a merge
     * in its setup stays green throughout.
     *
     * Asserting that recovery is skipped here would be encoding that bug, not testing for it.
     */
    @Test
    fun `V22 - a successful poll that merged nothing still permits recovery`() = runTest {
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = false)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(1)
    }

    /**
     * V22a — **recorded as N/A-by-construction on this platform, not as passing.** Read the reason before
     * treating this as coverage.
     *
     * The vector guards clients where a failed poll and an empty poll arrive as the *same value* (an empty
     * array), so that keying off "result is empty" silently treats "nothing answered" as "we're level".
     * Elsewhere V22a is the only vector that discriminates — V22 passes under the wrong implementation.
     *
     * That trap cannot occur here, and the reason is structural rather than tested: a failed retrieve
     * **throws** (`AutoRetryApiExecutor` rethrows once retries are exhausted; `AbstractSnodeApi` throws on
     * any non-2xx), so failure is an *exception* and empty is an *empty list* — different types, and the
     * poller never reaches [ExpiredConfigRecovery.markLocalStateLevelWithSwarm] on the failing path. This
     * client also polls a **single** snode per request, so there is no aggregate-of-many-snodes step in
     * which failures could be flattened into emptiness at all.
     *
     * So the assertion below exercises no code that V10 doesn't already cover — it documents the hazard
     * rather than testing against it, and counting it as a passing row would inflate apparent coverage.
     * The thing that actually protects this property is the type distinction above; keep it that way.
     */
    @Test
    fun `V22a - N-A by construction - a poll that answered nothing must not permit recovery`() = runTest {
        // Deliberately no markLocalStateLevelWithSwarm call: that is what a failed poll looks like here,
        // because the poller throws before reaching it.
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(0)
        assertRecoveryStillReachable()
    }

    /**
     * V22c — a poll that took in fewer messages than it fetched must not permit recovery.
     *
     * The tolerance itself is correct: a config message that won't parse or verify is skipped and the
     * rest are merged. What's wrong is reading that silence as "everything landed" — 2-of-3 merging is
     * indistinguishable from 3-of-3 unless the count is compared.
     */
    @Test
    fun `V22c - a poll that merged only some of what it fetched must not permit recovery`() = runTest {
        recovery.markMergeIncompleteForSwarm(userId.hexString)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(0)
        assertRecoveryStillReachable()
    }

    /**
     * V22c, the part that matters most and the one a per-poll check misses: the verdict has to be
     * **sticky for the session**.
     *
     * A message we couldn't merge is never offered again — the dedup table marks a hash as seen before
     * the merge is attempted, and the poller's `lastHash` advances on a successful *fetch* — so the very
     * next poll comes back completely clean while local state is still missing what that message
     * carried. A guard that only looks at the current poll therefore self-heals into the wrong answer
     * one poll later, which is worse than failing outright because nothing ever looks wrong again.
     */
    @Test
    fun `V22d - a later clean poll must not undo an earlier incomplete merge`() = runTest {
        recovery.markMergeIncompleteForSwarm(userId.hexString)

        // The next poll fetches nothing at all and looks perfectly healthy.
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = false)
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(0)
        assertRecoveryStillReachable()
    }

    /**
     * And a swarm withdrawn this way doesn't take unrelated swarms down with it.
     *
     * Deliberately unlabelled: the withdrawal being per-swarm is a consequence of V22c, not a vector of its
     * own. It carried "V22c" until a sweep found that label already on the test above.
     */
    @Test
    fun `withdrawing one swarm leaves others recoverable`() = runTest {
        recovery.markMergeIncompleteForSwarm(
            AccountId(IdPrefix.GROUP, ByteArray(32) { 9 }).hexString
        )
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(1)
    }


    /**
     * ...but the retrying is rate-limited, or releasing the claims would reintroduce the storm.
     *
     * Deliberately a backoff and **not** a cap on attempts. A cap would exclude a device whose stores keep
     * failing for the rest of the session — which is the same population the feature exists to repair, so
     * a transient network failure would cost a device its recovery entirely. See V13a.
     */
    @Test
    fun `retries after failure are rate-limited, not counted`() = runTest {
        coEvery { swarmApiExecutor.send(any(), any()) } throws RuntimeException("store rejected")
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        repeat(10) {
            recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        }

        // One round's worth, however many polls report it missing inside the backoff window.
        assertStoreCount(ATTEMPTS_PER_STORE)
    }

    /**
     * V13a — a store that failed transiently MUST be retried; only a store that **succeeded** bars the
     * hash for the session.
     *
     * Read as a pair with V13. V13 alone passes on a barred-on-attempt implementation, which is why the
     * spec carried the weaker wording for 39 revisions: barring on attempt buys none of the anti-storm
     * property and silently excludes any device whose one attempt hit a blip — on a feature that exists
     * for devices something has already gone wrong for.
     */
    @Test
    fun `V13a - a transiently failed store is retried once the backoff elapses`() = runTest {
        coEvery { swarmApiExecutor.send(any(), any()) } throws RuntimeException("transient")
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        assertStoreCount(ATTEMPTS_PER_STORE)

        // The blip passes, and so does the backoff window.
        coEvery { swarmApiExecutor.send(any(), any()) } answers {
            storeCalls.incrementAndGet()
            StoreMessageResponse(hash = h2, timestamp = Instant.EPOCH)
        }
        now += 61_000L

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(ATTEMPTS_PER_STORE + 1)
    }

    /**
     * V13h — a round needing more than the server's sub-request limit must be **chunked**, and all of it
     * must land.
     *
     * The server rejects an oversized batch *whole* rather than truncating it, and it surfaces as a request
     * failure rather than a size error — so it reads as a network problem and gets retried into the same
     * wall. One config can split into ~66 parts (`MAX_MULTIPART_SIZE / MAX_MESSAGE_SIZE`), each its own
     * store, so a single large config is already three times over.
     *
     * And it is the worst instance of this feature's recurring trap — a limit that excludes the very
     * population the repair exists for: **the accounts with the largest configs have the most to lose and
     * are exactly the ones whose recovery would be rejected wholesale.**
     *
     * Asserts the **boundary**, not eventual success — the fixture is 25 parts plus a delete, so it
     * genuinely crosses 20, and the assertion is on the observed chunk sizes. A test that only checked
     * "everything eventually stored" would pass on an implementation that got lucky with a small fixture.
     */
    @Test
    fun `V13h - a round exceeding the batch limit is chunked and all of it lands`() = runTest {
        val parts = (1..25).map { "P$it" }
        every { restoreSource.userConfigsToRestore(any()) } returns listOf(
            PendingRestore(
                label = "user config CONTACTS",
                push = ConfigPush(
                    messages = parts.map { Bytes(it.toByteArray()) },
                    seqNo = 7L,
                    obsoleteHashes = listOf("old-1"),
                ),
                claimedHashes = parts.toSet(),
                namespace = { CONTACTS_NAMESPACE },
            )
        )

        // Measure peak concurrency, which is what the batch window actually sees. The mock SUSPENDS, so a
        // chunk's requests are genuinely in flight together and the peak is the chunk size; a synchronous
        // mock would complete each call before the next began and report a peak of 1 whatever the
        // implementation did.
        var inFlight = 0
        var peakInFlight = 0
        coEvery { swarmApiExecutor.send(any(), any()) } coAnswers {
            inFlight++
            peakInFlight = maxOf(peakInFlight, inFlight)
            delay(1)
            inFlight--
            when (secondArg<SwarmApiRequest<*>>().api) {
                // Must keep setUp's delete branch: returning a store response for a delete throws a cast
                // error, which the retry wrapper then repeats — inflating the count by 4 and looking like
                // a chunking bug rather than a fixture one.
                is DeleteMessageApi -> DeleteMessageApi.SuccessResponse(1, 1)
                else -> {
                    storeCalls.incrementAndGet()
                    StoreMessageResponse(hash = h2, timestamp = Instant.EPOCH)
                }
            }
        }

        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf("P1")))

        // All 25 stores plus the delete went out...
        coVerify(exactly = 26) { swarmApiExecutor.send(any(), any()) }
        // ...in chunks that never exceeded the limit...
        assertTrue(peakInFlight <= 20, "a chunk exceeded the limit: peak was $peakInFlight")
        // ...and the peak proves a chunk was genuinely observed. Without this the assertion above passes
        // on a fully sequential implementation, which would report a peak of 1 and prove nothing about
        // the boundary — the degenerate case this vector exists to rule out.
        assertTrue(peakInFlight > 1, "no chunk was observed at all: peak was $peakInFlight")
    }

    /**
     * V13g — the bar on a successfully re-stored hash must be **time-bounded**, not session-scoped.
     *
     * The bar exists to stop a swarm reporting the same hash missing on every poll costing a store every
     * poll — a burst measured in seconds. "Never again this session" is unbounded in time, and a session can
     * outlive the 30-day config TTL (a backgrounded mobile client, or desktop, which runs for weeks by
     * design). In that window a hash we successfully put back can expire a *second* time, and a
     * session-scoped bar blocks the recovery that should put it back — excluding long-lived sessions, which
     * is exactly where configs expire.
     *
     * ⚠️ Driven by **advancing the clock**, never by rebuilding the recovery instance. A fresh instance
     * clears in-memory state, so a restart-driven version of this test passes on the session-scoped
     * implementation too — it would be measuring construction rather than expiry. A session-scoped
     * implementation passes V13/V13a/V13b and fails only this.
     */
    @Test
    fun `V13g - a successfully re-stored hash is barred for a bounded time, not the session`() = runTest {
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        assertStoreCount(1)

        // Still barred a few minutes later — the anti-storm property has to survive a burst of polls.
        now += 5.minutes.inWholeMilliseconds
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        assertStoreCount(1)

        // Past the bar, the same hash reported missing again is a genuine second expiry: re-store it.
        now += 1.hours.inWholeMilliseconds
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(2)
    }

    /**
     * V13b — "stored" must mean every *sub-response's own* code was 2xx, not merely that the outer call
     * returned. A batch returns 200 while its sub-requests carry their own codes, so a client that discards
     * the response bars every hash for the session having written nothing — and the bar's bookkeeping looks
     * perfectly correct while the semantics are wrong.
     *
     * Here each message is its own `execute()`, which throws on its own non-2xx, so a multipart config with
     * one bad part stays retryable **in full**. This test drives that: part 2 of 3 fails.
     */
    @Test
    fun `V13b - one failed part of a multipart store leaves the whole config retryable`() = runTest {
        givenRestorable(claimedHashes = setOf("P1", "P2", "P3"), messageCount = 3)
        // One part stores, the rest fail permanently — including through the retry wrapper, which is what
        // makes this a genuine partial store rather than a transient blip that retries into success. Keyed
        // on a count of successes rather than of calls, so it doesn't depend on which part runs first.
        var stored = 0
        coEvery { swarmApiExecutor.send(any(), any()) } answers {
            if (stored >= 1) throw RuntimeException("sub-request 500")
            stored++
            storeCalls.incrementAndGet()
            StoreMessageResponse(hash = h2, timestamp = Instant.EPOCH)
        }
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf("P2")))

        // The round failed, so nothing is barred — including the parts that did store.
        coEvery { swarmApiExecutor.send(any(), any()) } answers {
            storeCalls.incrementAndGet()
            StoreMessageResponse(hash = h2, timestamp = Instant.EPOCH)
        }
        now += 61_000L
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf("P2")))

        // All three parts re-stored on the retry, not just the one that failed.
        verify(exactly = 2) { restoreSource.userConfigsToRestore(any()) }
    }

    /**
     * V13c — the wait **doubles** per consecutive failed round. A flat-rate implementation passes V13a and
     * fails only this.
     */
    @Test
    fun `V13c - the second wait is 120s, not 60`() = runTest {
        coEvery { swarmApiExecutor.send(any(), any()) } throws RuntimeException("still failing")
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        assertStoreCount(ATTEMPTS_PER_STORE)

        // 61s clears the first 60s window: a second round runs and also fails.
        now += 61_000L
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        assertStoreCount(2 * ATTEMPTS_PER_STORE)

        // Another 61s is NOT enough now — the window has doubled to 120s.
        now += 61_000L
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        assertStoreCount(2 * ATTEMPTS_PER_STORE)

        // ...but 121s from the second failure is.
        now += 61_000L
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        assertStoreCount(3 * ATTEMPTS_PER_STORE)
    }

    /**
     * V13d — retry never stops. The ceiling bounds the *interval*, never the number of attempts: a cap on
     * attempts is the population-exclusion shape this feature has already produced twice, and it would
     * exclude exactly the swarms most in need of repair.
     */
    @Test
    fun `V13d - retrying never stops, however long the session fails`() = runTest {
        coEvery { swarmApiExecutor.send(any(), any()) } throws RuntimeException("permanently failing")
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        // Twenty hours of a persistently failing swarm, stepping past each (growing) window.
        // Counted via the gather rather than via storeCalls, which only counts stores that SUCCEED — a
        // detail that made the first version of this test read 0 rounds against a working implementation.
        repeat(40) {
            now += 31.minutes.inWholeMilliseconds
            recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        }

        // Every single step past the ceiling produced a round — nothing ever gave up.
        verify(exactly = 40) { restoreSource.userConfigsToRestore(any()) }
    }

    /**
     * V13 — a swarm reporting the same hash missing on every poll must cost one store, not one per poll.
     *
     * ⚠️ This test does **not** advance the clock, so it cannot tell a session-scoped bar from a
     * time-bounded one — both pass it. Hence "while the bar holds" in the name rather than a bare "once":
     * the unqualified claim would be a property no assertion here checks. V13g is the only test that
     * separates them.
     */
    @Test
    fun `V13 - a hash reported missing on every poll is put back once while the bar holds`() = runTest {
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        repeat(3) {
            recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        }

        assertStoreCount(1)
    }

    /**
     * V18 — one missing part re-stores the whole config. There's no way to do otherwise: the active
     * hashes come back as an unordered set, so a part hash can't be mapped to its index in the message
     * vector `push()` returns. Re-storing the present parts is harmless anyway — they're byte-identical,
     * so it's a no-op TTL refresh.
     */
    @Test
    fun `V18 - one missing part of a multipart config re-stores all of them`() = runTest {
        givenRestorable(claimedHashes = setOf("P1", "P2", "P3"), messageCount = 3)
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf("P2")))

        assertStoreCount(3)
    }

    /**
     * And every part is then claimed, so a later poll naming a *different* part changes nothing.
     *
     * Deliberately unlabelled: this is a consequence of V18 on this client, not a vector of its own. It
     * carried "V13b" until a sweep found that label already on a different test above — the same silent
     * collision that is only supposed to happen *between* clients.
     */
    @Test
    fun `every part of a re-stored multipart config is claimed, not just the missing one`() = runTest {
        givenRestorable(claimedHashes = setOf("P1", "P2", "P3"), messageCount = 3)
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf("P2")))
        assertStoreCount(3)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf("P1")))
        assertStoreCount(3)
    }

    /**
     * V17 — `push()` drains libsession's obsolete-hash set and clears it unconditionally, even for a
     * clean config. So a recovery path that takes the list and throws it away loses those hashes
     * permanently and leaves the superseded messages on the swarm forever. The delete has to be issued
     * exactly as a normal push would.
     */
    @Test
    fun `V17 - obsolete hashes returned by a re-store are deleted`() = runTest {
        givenRestorable(claimedHashes = setOf(h2), messageCount = 1, obsoleteHashes = listOf("old-1"))
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        coVerify(exactly = 1) { deleteMessageApiFactory.create(any(), listOf("old-1")) }
    }

    /**
     * A failed store must not take its config's obsolete hashes with it.
     *
     * `push()` has already cleared them, so deleting them without storing the replacement removes the
     * swarm's older copy without adding the new one — and a device restoring from seed in that window gets
     * *nothing* rather than stale state. Skipping the delete leaks them instead, which is close to free:
     * obsolete hashes are never TTL-extended, so by the time our refreshed current hash has expired an
     * un-refreshed older one is long gone.
     */
    @Test
    fun `a failed store does not delete its config's obsolete hashes`() = runTest {
        givenRestorable(claimedHashes = setOf(h2), messageCount = 1, obsoleteHashes = listOf("old-1"))
        coEvery { swarmApiExecutor.send(any(), any()) } throws RuntimeException("store rejected")
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        coVerify(exactly = 0) { deleteMessageApiFactory.create(any(), any()) }

        // Reachability control: the same fixture DOES delete once the store succeeds, so the absence above
        // is the store failing rather than the delete path never being wired.
        coEvery { swarmApiExecutor.send(any(), any()) } answers {
            when (secondArg<SwarmApiRequest<*>>().api) {
                is DeleteMessageApi -> DeleteMessageApi.SuccessResponse(1, 1)
                else -> {
                    storeCalls.incrementAndGet()
                    StoreMessageResponse(hash = h2, timestamp = Instant.EPOCH)
                }
            }
        }
        now += 2.hours.inWholeMilliseconds
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        coVerify(exactly = 1) { deleteMessageApiFactory.create(any(), listOf("old-1")) }
    }

    /**
     * V17b — V17's negative counterpart: with nothing obsolete there is no delete at all. The natural bug
     * is issuing an empty delete.
     *
     * An absence assertion, so it carries a reachability control — the `assertStoreCount(1)` below, which a
     * dead harness could not satisfy. This is the
     * normal case for a member re-store, where libsession never hands back the hashes — an empty list
     * is the expected result, not a sign anything failed.
     */
    @Test
    fun `V17b - no obsolete hashes means no delete request`() = runTest {
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(1)
        coVerify(exactly = 0) { deleteMessageApiFactory.create(any(), any()) }
    }

    /** Every cause, so that a cause added later cannot quietly become one that authorises a store. */
    @Test
    fun `an inconclusive report triggers nothing, whatever made it inconclusive`() = runTest {
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        listOf(
            ConfigExpiryReport.Inconclusive.ExtendNotRequested,
            ConfigExpiryReport.Inconclusive.NothingAsked,
            ConfigExpiryReport.Inconclusive.NoUsableSubResponse,
            ConfigExpiryReport.Inconclusive.ResponseUnreadable,
        ).forEach { recovery.onUserConfigsChecked(userAuth(), it) }

        assertStoreCount(0)
        assertRecoveryStillReachable()
    }

    @Test
    fun `a report with nothing missing triggers nothing`() = runTest {
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(emptySet()))

        assertStoreCount(0)
        assertRecoveryStillReachable()
    }

    @Test
    fun `recovery waits for the foreground`() = runTest {
        appVisible.value = false
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(0)

        // Restore the foreground before the control: with the app backgrounded for the whole test, the
        // control could not produce a store either, and a control that can't succeed proves nothing.
        appVisible.value = true
        assertRecoveryStillReachable()
    }

    /**
     * V13f — a throwing inspection must not escape, and this is the load-bearing half.
     *
     * The handoff sits at the very end of `Poller.poll()` with nothing above it to catch, and `gather` runs
     * libsession code — `push()` throws when a config has no encryption keys. So an escaping exception
     * doesn't merely mis-handle a hash: it fails the **entire poll**, every poll, because the condition
     * doesn't clear. No messages processed, no configs merged, failure counter climbing.
     *
     * A best-effort repair feature must never be able to break the thing it rides on — recovery's whole
     * premise is that polling continues, so this removes its own precondition and takes everything else
     * polling does with it. And the failure signature is that every recovery test still passes, which is
     * why this needs its own vector rather than being inferred from the others.
     *
     * The inspection is the only part that runs library code that can throw, and it is also the part that
     * *looks* like a pure read — which is why it doesn't get wrapped.
     */
    @Test
    fun `V13f - a throwing inspection does not escape, bar anything, or consume the backoff`() = runTest {
        every { restoreSource.userConfigsToRestore(any()) } throws
                IllegalStateException("Cannot push data without an encryption key!")
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        // Must not throw — if this escapes, the caller is the poll itself.
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        // Nothing barred and no backoff consumed: an inspection that threw reached no verdict, so the
        // hash stays retryable and the next poll tries again immediately.
        every { restoreSource.userConfigsToRestore(any()) } returns listOf(
            PendingRestore(
                label = "user config CONTACTS",
                push = ConfigPush(
                    messages = listOf(Bytes("config-data".toByteArray())),
                    seqNo = 7L,
                    obsoleteHashes = emptyList(),
                ),
                claimedHashes = setOf(h2),
                namespace = { CONTACTS_NAMESPACE },
            )
        )
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        assertStoreCount(1)
    }

    /**
     * V13e — a hash a **guard** ruled out is barred like a success, not treated as a retryable failure.
     *
     * (Two of the Session clients independently used "V13b" for two different tests, which is silent by
     * construction and only surfaces when suites are compared — which is this feature's entire
     * verification model.)
     *
     * Folding guard-rejections into "failure" costs no requests, which is why it doesn't look like a
     * problem — but the gather re-runs on every poll, taking the config write lock and re-logging the same
     * rejection every few seconds for the rest of the session. Only a store that *failed* is retryable.
     */
    @Test
    fun `V13e - a hash ruled out by a guard is not re-inspected on a later poll`() = runTest {
        every { restoreSource.userConfigsToRestore(any()) } returns emptyList()
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))
        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        // Gathered once and then left alone, however many polls keep reporting it missing.
        verify(exactly = 1) { restoreSource.userConfigsToRestore(any()) }
    }

    /**
     * The one must-not vector that **cannot** use [assertRecoveryStillReachable], because its premise *is*
     * the death mode: "nothing was eligible" and "the harness returned nothing" are the same observation.
     * A reachability control here would be asserting that an empty gather produces a store.
     *
     * So it proves the path was reached a different way — by asserting the guards were all passed and the
     * gather actually ran. That is the assertion a dead harness cannot satisfy.
     */
    @Test
    fun `nothing eligible means no requests`() = runTest {
        every { restoreSource.userConfigsToRestore(any()) } returns emptyList()
        recovery.markLocalStateLevelWithSwarm(userId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onUserConfigsChecked(userAuth(), ConfigExpiryReport.Checked(setOf(h2)))

        verify(exactly = 1) { restoreSource.userConfigsToRestore(setOf(h2)) }
        assertStoreCount(0)
    }

    /**
     * V23d — a group already flagged expired must have the flag cleared **by the re-store itself**.
     *
     * The reactive path cannot do this. It clears the flag when a keys message is *handled*, and the device
     * that re-stored the bytes already holds that hash, so it may never handle it again — leaving a banner up
     * permanently over keys that are back on the swarm. So a successful keys re-store emits directly.
     */
    @Test
    fun `V23d - a successful keys re-store announces itself so the flag can be cleared`() = runTest {
        givenGroupKeysRestorable()
        recovery.markLocalStateLevelWithSwarm(groupId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onGroupConfigsChecked(groupId, authFor(groupId.hexString), keysMissing)

        assertEquals(listOf(groupId), recovery.keysRestored.replayCache)
    }

    /**
     * V23c — a keys re-store that FAILED must not announce anything, so the flag stays up.
     *
     * What this pins is only that a wholly failed round is silent. It does NOT pin per-restore emission over
     * per-round — every store fails here, so a round-level signal stays silent too and both implementations
     * pass. The mixed round below is the test that separates them; this comment used to claim that job and
     * was wrong, which a mutation to round-level emission demonstrated by surviving.
     */
    @Test
    fun `V23c - a failed keys re-store announces nothing, so the flag stands`() = runTest {
        givenGroupKeysRestorable()
        coEvery { swarmApiExecutor.send(any(), any()) } throws RuntimeException("store failed")
        recovery.markLocalStateLevelWithSwarm(groupId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onGroupConfigsChecked(groupId, authFor(groupId.hexString), keysMissing)

        assertEquals(emptyList(), recovery.keysRestored.replayCache)
    }

    /**
     * A restore that is not the keys config must not clear the banner however well it goes — the flag is the
     * keys config's alone, and info or members landing says nothing about whether the keys are back.
     */
    @Test
    fun `a successful non-keys restore announces nothing`() = runTest {
        every { restoreSource.groupConfigsToRestore(groupId, any()) } returns listOf(
            PendingRestore(
                label = "group info for $groupId",
                push = ConfigPush(listOf(Bytes("info".toByteArray())), 7L, emptyList()),
                claimedHashes = setOf("info-1"),
                namespace = { GROUP_INFO_NAMESPACE },
            )
        )
        recovery.markLocalStateLevelWithSwarm(groupId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onGroupConfigsChecked(
            groupId,
            authFor(groupId.hexString),
            ConfigExpiryReport.Checked(setOf("info-1")),
        )

        assertStoreCount(1)
        assertEquals(emptyList(), recovery.keysRestored.replayCache)
    }

    /**
     * The case that actually distinguishes per-restore emission from per-round: info lands, keys does not.
     *
     * V23c alone does NOT pin this, and I claimed it did. It fails *every* store, so `outcomes.any { it }`
     * is false and a round-level implementation stays silent too — both pass. Verified by mutating the
     * emission to round-level: V23c, V23d and the non-keys control all survived it. This test is what dies.
     *
     * Getting it wrong would clear the banner on a group whose keys never made it back, on the strength of
     * its *info* config having stored.
     */
    @Test
    fun `a round where info lands and keys fails announces nothing`() = runTest {
        // A factory that hands back a distinguishable api per namespace, so the keys store can be failed
        // precisely rather than by call ordering — which is concurrent within a chunk and would make this
        // test depend on which store happened to run first.
        val keysApis = mutableSetOf<StoreMessageApi>()
        val factory = mockk<StoreMessageApi.Factory>()
        every { factory.create(any(), any(), any()) } answers {
            mockk<StoreMessageApi>(relaxed = true).also {
                if (thirdArg<Int>() == GROUP_KEYS_NAMESPACE) keysApis += it
            }
        }
        recovery = ExpiredConfigRecovery(
            restoreSource = restoreSource,
            clock = clock,
            appVisibilityManager = appVisibilityManager,
            swarmApiExecutor = swarmApiExecutor,
            storeMessageApiFactory = factory,
            deleteMessageApiFactory = deleteMessageApiFactory,
        )
        coEvery { swarmApiExecutor.send(any(), any()) } answers {
            val request = secondArg<SwarmApiRequest<*>>()
            if (request.api in keysApis) throw RuntimeException("keys store 500")
            storeCalls.incrementAndGet()
            StoreMessageResponse(hash = h2, timestamp = Instant.EPOCH)
        }

        every { restoreSource.groupConfigsToRestore(groupId, any()) } returns listOf(
            PendingRestore(
                label = "group info for $groupId",
                push = ConfigPush(listOf(Bytes("info".toByteArray())), 7L, emptyList()),
                claimedHashes = setOf("info-1"),
                namespace = { GROUP_INFO_NAMESPACE },
            ),
            PendingRestore(
                label = "group keys for $groupId",
                push = ConfigPush(listOf(Bytes("keys-bytes".toByteArray())), 0L, emptyList()),
                claimedHashes = setOf("keys-1"),
                isGroupKeys = true,
                namespace = { GROUP_KEYS_NAMESPACE },
            ),
        )
        recovery.markLocalStateLevelWithSwarm(groupId.hexString, mergedConfigMessagesForDiagnosticsOnly = true)

        recovery.onGroupConfigsChecked(
            groupId,
            authFor(groupId.hexString),
            ConfigExpiryReport.Checked(setOf("info-1", "keys-1")),
        )

        // Info stored, so the round is not a failure — and the banner must still stand.
        assertEquals(emptyList(), recovery.keysRestored.replayCache)
    }

    private val keysMissing = ConfigExpiryReport.Checked(setOf("keys-1"))

    private fun givenGroupKeysRestorable() {
        every { restoreSource.groupConfigsToRestore(groupId, any()) } returns listOf(
            PendingRestore(
                label = "group keys for $groupId",
                push = ConfigPush(listOf(Bytes("keys-bytes".toByteArray())), 0L, emptyList()),
                claimedHashes = setOf("keys-1"),
                isGroupKeys = true,
                namespace = { GROUP_KEYS_NAMESPACE },
            )
        )
    }

    private fun givenRestorable(
        claimedHashes: Set<String>,
        messageCount: Int,
        obsoleteHashes: List<String> = emptyList(),
    ) {
        every { restoreSource.userConfigsToRestore(any()) } returns listOf(
            PendingRestore(
                label = "user config CONTACTS",
                push = ConfigPush(
                    messages = List(messageCount) { Bytes("config-data-$it".toByteArray()) },
                    seqNo = 7L,
                    obsoleteHashes = obsoleteHashes,
                ),
                claimedHashes = claimedHashes,
                namespace = { CONTACTS_NAMESPACE },
            )
        )
    }

    /**
     * Counts `store` requests. Deletes go through the same executor, so they're excluded by the API
     * each request was built from rather than by counting calls.
     */
    private suspend fun assertStoreCount(expected: Int) {
        coVerify(exactly = expected) {
            swarmApiExecutor.send(any(), match { it.api is StoreMessageApi })
        }
    }

    /**
     * Positive control for a must-not vector, and the reason every one of them calls it.
     *
     * A negative assertion cannot establish anything about its own harness: "no store happened" is
     * produced equally by the guard correctly declining and by the path never running at all. Killing a
     * stub in a way that dies *quietly* — returning an empty list, which is a perfectly legitimate
     * "nothing was eligible" — made all nine must-not vectors here pass green while executing none of the
     * code they name. A death that *throws* doesn't show this, which is why it has to be a quiet one.
     *
     * So each must-not vector finishes by proving a store is still reachable through the same harness and
     * the same instance. A different swarm and a different hash are used so this can't perturb whatever
     * the vector just asserted.
     */
    private suspend fun assertRecoveryStillReachable() {
        val controlSwarm = AccountId(IdPrefix.STANDARD, ByteArray(32) { 7 }).hexString
        val before = storeCallCount()

        recovery.markLocalStateLevelWithSwarm(
            controlSwarm,
            mergedConfigMessagesForDiagnosticsOnly = true,
        )
        recovery.onUserConfigsChecked(
            authFor(controlSwarm),
            ConfigExpiryReport.Checked(setOf("control-hash")),
        )

        assertTrue(
            storeCallCount() > before,
            "Harness is dead: a fully satisfied guard produced no store, so the assertion above " +
                    "proves nothing about the guard under test.",
        )
    }

    private fun storeCallCount(): Int = storeCalls.get()

    private fun authFor(swarmPubKeyHex: String): SwarmAuth = mockk<SwarmAuth>().also {
        every { it.accountId } returns AccountId(swarmPubKeyHex)
    }

    private fun userAuth(): SwarmAuth = mockk<SwarmAuth>().also {
        every { it.accountId } returns userId
    }

    private companion object {
        /** Hardcoded rather than read from libsession's native `Namespace`, which unit tests can't load. */
        const val CONTACTS_NAMESPACE = 3
        const val GROUP_KEYS_NAMESPACE = 12
        const val GROUP_INFO_NAMESPACE = 13

        /**
         * Requests one *failing* store produces: `retryWithUniformInterval` wraps each store with three
         * retries, so a failed recovery round costs four requests per message rather than one. Worth
         * naming — it's the multiplier on the round cap, so the real storm bound is
         * `MAX_RECOVERY_ROUNDS_PER_SWARM × this × messages-per-config`.
         */
        const val ATTEMPTS_PER_STORE = 4
    }
}

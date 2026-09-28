package org.thoughtcrime.securesms.configs

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.ConfigPush
import network.loki.messenger.libsession_util.util.GroupInfo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.snode.model.StoreMessageResponse
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.MutableGroupConfigs
import org.session.libsession.utilities.UserConfigs
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.ConfigExpiryReport
import org.thoughtcrime.securesms.api.snode.StoreMessageApi
import org.thoughtcrime.securesms.api.snode.groupExpiredAfterPoll
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.MockLoggingRule
import java.net.SocketTimeoutException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The force-rekey of last resort, and what refuses it.
 *
 * Every guard here produces the same visible result as doing nothing, so each test asserts on whether a
 * rekey was issued rather than on an absence of errors. A rekey is the one irreversible, every-member
 * visible write in this feature; "it did not crash" is not evidence it declined.
 *
 * V25b, that the backfill keeps working with the force rekey removed, has no standing test. Both live on
 * one object, so a coupling between them needs only a reference to a sibling field, which reflection over
 * signatures cannot see. The check is to delete the rekey and its tests and rebuild.
 */
class ForceRekeyTest {
    @get:Rule
    val loggingRule = MockLoggingRule()

    private val groupId = AccountId(IdPrefix.GROUP, ByteArray(32) { 3 })

    private lateinit var configFactory: ConfigFactoryProtocol
    private lateinit var configs: MutableGroupConfigs
    private lateinit var forceRekey: ExpiredConfigRecovery
    private var now = 5_000_000L
    private lateinit var clock: SnodeClock

    // The swarm, and what each node holds for the keys namespace. The backfill's first node is the
    // poll's own, [nodeA].
    private val nodeA = node("a")
    private val nodeB = node("b")
    private val nodeC = node("c")
    private val nodeAnswers = mutableMapOf<Snode, suspend () -> List<RetrieveMessageResponse.Message>>()
    private lateinit var swarmApiExecutor: SwarmApiExecutor
    private lateinit var swarmDirectory: SwarmDirectory
    private var storesFail = false

    // The group's keys config as libsession presents it: the active hashes, and the bytes retained for
    // them. A merge retains the bytes of every message it loads, which is what libsession's retention does.
    private var activeKeysHashes = listOf("keys-1")
    private val retained = mutableMapOf<String, ByteArray>()

    private val keysHashes get() = activeKeysHashes.toSet()
    private val everyKeysHashMissing get() = ConfigExpiryReport.Checked(keysHashes)

    @Before
    fun setUp() {
        configFactory = mockk(relaxed = true)
        configs = mockk(relaxed = true)
        every { configFactory.dangerouslyAccessMutableGroupConfigs(groupId) } returns (configs to {})
        every { configFactory.dangerouslyAccessGroupConfigs(groupId) } returns (configs to {})
        every { configs.groupKeys.activeHashes() } answers { activeKeysHashes }
        every { configs.groupKeys.activeKeyMessages() } answers { retained.toMap() }
        every { configFactory.mergeGroupConfigMessages(groupId, any(), any(), any()) } answers {
            val keys = secondArg<List<ConfigMessage>>()
            keys.forEach { retained[it.hash] = it.data }
            keys.size
        }

        // Every node answers, and holds nothing, unless a test says otherwise.
        for (node in listOf(nodeA, nodeB, nodeC)) nodeAnswers[node] = { emptyList() }
        swarmApiExecutor = mockk()
        coEvery { swarmApiExecutor.send(any(), any()) } coAnswers {
            val request = secondArg<SwarmApiRequest<*>>()
            if (request.api is StoreMessageApi) {
                if (storesFail) throw RuntimeException("store 500")
                StoreMessageResponse(hash = "stored", timestamp = Instant.EPOCH)
            } else {
                RetrieveMessageResponse(messages = nodeAnswers.getValue(request.swarmNodeOverride!!)())
            }
        }
        swarmDirectory = mockk()
        givenFetchedSwarm(unreadable = 0)
        // The cached view, which agrees with the network until a test says otherwise.
        coEvery { swarmDirectory.getSwarm(groupId.hexString) } returns listOf(nodeA, nodeB, nodeC)

        clock = mockk<SnodeClock>()
        every { clock.currentTimeMillis() } answers { now }

        forceRekey = recovery()
    }

    /**
     * V25 — every node of the swarm answered and none holds the message, the expiry check says every keys
     * hash is gone, nothing is held here, and the device is an admin: exactly one rekey.
     */
    @Test
    fun `V25 - an admin rekeys a group no node of the swarm can repair`() = runTest {
        givenGroup(admin = true)
        val pollToken = completedPoll()

        val outcome = backfill()

        assertEquals(KeysBackfill.Failed, outcome)
        // Every node was asked, which is what makes the failure mean the swarm and not one node.
        for (node in listOf(nodeA, nodeB, nodeC)) {
            coVerify(exactly = 1) { swarmApiExecutor.send(any(), match { it.swarmNodeOverride == node }) }
        }
        assertTrue(rekeyAfter(outcome, pollToken))
        assertFalse(rekeyAfter(outcome, pollToken))
        verify(exactly = 1) { configs.rekey() }
    }

    /**
     * V25h — the poll's node holds nothing, but another node of the swarm still has the message. It is
     * fetched from there and merged, so the bytes are held and the ordinary re-store puts it back, and there
     * is no rekey.
     *
     * The merge is the ordinary one, and it is what persists the dump; with the config factory mocked here,
     * that persistence is not itself observed.
     */
    @Test
    fun `V25h - a copy on another node is merged rather than replaced by a rekey`() = runTest {
        givenGroup(admin = true)
        val pollToken = completedPoll()
        nodeAnswers[nodeB] = { listOf(message("keys-1")) }

        val outcome = backfill()

        assertEquals(KeysBackfill.Captured, outcome)
        assertTrue("keys-1" in retained)
        // Stopped once it had the bytes: the third node is never asked.
        coVerify(exactly = 0) { swarmApiExecutor.send(any(), match { it.swarmNodeOverride == nodeC }) }
        assertFalse(rekeyAfter(outcome, pollToken))
        verify(exactly = 0) { configs.rekey() }
    }

    /**
     * V25i — every node that answered holds nothing, but one did not answer. That node is not evidence the
     * message is gone, so the attempt is inconclusive: no rekey, and it is retried once the ordinary
     * backfill bar has passed.
     *
     * A timeout reaches this layer as the IO exception OkHttp's call and read timeouts throw, rethrown by the
     * executor once its retries are spent, so that is the shape used.
     */
    @Test
    fun `V25i - a node that did not answer makes the attempt inconclusive`() = runTest {
        givenGroup(admin = true)
        val pollToken = completedPoll()
        nodeAnswers[nodeB] = { throw SocketTimeoutException("timeout") }

        val outcome = backfill()

        assertEquals(KeysBackfill.Inconclusive, outcome)
        // Asked past the node that timed out, so its silence did not end the attempt early.
        coVerify(exactly = 1) { swarmApiExecutor.send(any(), match { it.swarmNodeOverride == nodeC }) }
        assertFalse(rekeyAfter(outcome, pollToken))
        verify(exactly = 0) { configs.rekey() }

        // Barred like any attempt, then retried: with every node answering this time, it fails, and
        // the rekey goes ahead.
        assertEquals(KeysBackfill.NotAttempted, backfill())
        now += RESTORED_HASH_BAR_MS
        nodeAnswers[nodeB] = { emptyList() }
        val retry = backfill()
        assertEquals(KeysBackfill.Failed, retry)
        assertTrue(rekeyAfter(retry, completedPoll()))
    }

    /**
     * V25j — a node that did not answer is dropped from the cached swarm, and on the next attempt every
     * node left on the cached list answers empty. That is not the swarm answering: the node that was
     * dropped is still in the swarm and still has not answered, so the attempt stays inconclusive. A
     * failure is judged against the swarm as fetched for that attempt.
     */
    @Test
    fun `V25j - a node dropped from the cached swarm still has to answer`() = runTest {
        givenGroup(admin = true)
        nodeAnswers[nodeB] = { throw SocketTimeoutException("timeout") }

        assertEquals(KeysBackfill.Inconclusive, backfill())

        // The pool drops the node that timed out; the network still lists it.
        coEvery { swarmDirectory.getSwarm(groupId.hexString) } returns listOf(nodeA, nodeC)
        now += RESTORED_HASH_BAR_MS

        assertEquals(KeysBackfill.Inconclusive, backfill())
        coVerify(exactly = 2) { swarmApiExecutor.send(any(), match { it.swarmNodeOverride == nodeB }) }
        assertFalse(rekeyAfter(KeysBackfill.Inconclusive, completedPoll()))

        // Reachability control: once that node answers too, the attempt fails and the rekey goes ahead.
        now += RESTORED_HASH_BAR_MS
        nodeAnswers[nodeB] = { emptyList() }
        val outcome = backfill()
        assertEquals(KeysBackfill.Failed, outcome)
        assertTrue(rekeyAfter(outcome, completedPoll()))
        verify(exactly = 1) { configs.rekey() }
    }

    /**
     * V25k — a backfill fails in one poll, but another guard refuses that poll's rekey. The next poll is
     * inside the backfill bar, so no backfill runs, and every other guard now passes. There is no rekey:
     * only a failure found by this poll's own attempt authorises one, since a peer may have put the keys
     * back since the earlier attempt.
     */
    @Test
    fun `V25k - a failure from an earlier poll does not authorise this poll's rekey`() = runTest {
        givenGroup(admin = true)

        // Poll N: the backfill fails, and the poll never marked us level.
        val pollN = forceRekey.beginPoll(groupId.hexString)
        val failed = backfill()
        assertEquals(KeysBackfill.Failed, failed)
        assertFalse(rekeyAfter(failed, pollN))

        // Poll N+1: level now, but the backfill is barred and establishes nothing.
        val pollN1 = completedPoll()
        val barred = backfill()
        assertEquals(KeysBackfill.NotAttempted, barred)
        assertFalse(rekeyAfter(barred, pollN1))
        verify(exactly = 0) { configs.rekey() }

        // Reachability control: past the bar, a poll whose own attempt fails does rekey.
        now += RESTORED_HASH_BAR_MS
        val pollN2 = completedPoll()
        val again = backfill()
        assertEquals(KeysBackfill.Failed, again)
        assertTrue(rekeyAfter(again, pollN2))
        verify(exactly = 1) { configs.rekey() }
    }

    /**
     * A swarm listing with an entry that could not be read names a node that is never asked, so the attempt
     * cannot fail, whatever the nodes that were read say.
     */
    @Test
    fun `an unreadable entry in the swarm listing makes the attempt inconclusive`() = runTest {
        givenGroup(admin = true)
        givenFetchedSwarm(unreadable = 1)

        val outcome = backfill()

        assertEquals(KeysBackfill.Inconclusive, outcome)
        assertFalse(rekeyAfter(outcome, completedPoll()))

        // Reachability control: the same listing, fully read, fails, and the rekey goes ahead.
        now += RESTORED_HASH_BAR_MS
        givenFetchedSwarm(unreadable = 0)
        val readable = backfill()
        assertEquals(KeysBackfill.Failed, readable)
        assertTrue(rekeyAfter(readable, completedPoll()))
    }

    /** A swarm that cannot be looked up has not answered either. */
    @Test
    fun `a swarm that cannot be looked up makes the attempt inconclusive`() = runTest {
        givenGroup(admin = true)
        coEvery { swarmDirectory.fetchSwarmCounted(groupId.hexString) } throws SocketTimeoutException("timeout")

        assertEquals(KeysBackfill.Inconclusive, backfill())
    }

    /**
     * The expiry check has to say every keys hash is gone. A backfill that failed across the whole swarm
     * says only that this device cannot get the bytes, not that the keys are gone.
     */
    @Test
    fun `no rekey unless the expiry check says every keys hash is gone`() {
        givenGroup(admin = true)
        activeKeysHashes = listOf("keys-1", "keys-2")

        for (report in listOf(
            null,
            ConfigExpiryReport.Inconclusive.NoUsableSubResponse,
            ConfigExpiryReport.Checked(setOf("keys-1")),
        )) {
            assertFalse(rekey(report = report))
        }
        verify(exactly = 0) { configs.rekey() }

        // Reachability control: the same fixture with both gone does rekey.
        assertTrue(rekey(report = ConfigExpiryReport.Checked(setOf("keys-1", "keys-2"))))
        verify(exactly = 1) { configs.rekey() }
    }

    /**
     * V25a — a member must not rekey, and must not appear to try.
     *
     * A member holds no signing key, so an attempted rekey would fail at signing — surfacing as an error on
     * a path that was never applicable to this device at all.
     */
    @Test
    fun `V25a - a member does not rekey and does not appear to try`() {
        givenGroup(admin = false)

        assertFalse(rekey())

        verify(exactly = 0) { configs.rekey() }

        // Reachability control: the same instance, same fixture, must still rekey for an admin — otherwise
        // "no rekey" is produced equally by the path never running.
        givenGroup(admin = true)
        assertTrue(rekey())
        verify(exactly = 1) { configs.rekey() }
    }

    /**
     * V25c — the storm guard. Two admins reaching this for the same group in one window must produce
     * bounded rekeys, not one per admin per poll.
     *
     * Tested as repeated calls for one group, which is this device's half of that: each device rekeys at
     * most once per interval. Asserting on the count rather than on the return value, because a guard that
     * returned false while still rekeying would pass a return-value assertion.
     */
    @Test
    fun `V25c - repeated attempts within the window produce one rekey`() {
        givenGroup(admin = true)

        assertTrue(rekey())
        assertFalse(rekey())
        assertFalse(rekey())

        verify(exactly = 1) { configs.rekey() }

        // Two hours on: still refused. This is the step that pins the interval's LENGTH rather than its
        // existence — advancing straight past the guard would pass just as well against a one-hour one, and
        // the whole point of the value is that a rekey is far more expensive to repeat than a re-store.
        now += 2 * 60 * 60 * 1000L
        assertFalse(rekey())
        verify(exactly = 1) { configs.rekey() }

        // ...and it is a guard, not a permanent block.
        now += 23 * 60 * 60 * 1000L
        assertTrue(rekey())
        verify(exactly = 2) { configs.rekey() }
    }

    /**
     * V25d — a rekey encrypts to THIS DEVICE'S view of members, and this path fires precisely on
     * devices whose config state is degraded. A member added while we were away — not yet merged here —
     * would be silently dropped by a rekey issued from that stale view.
     *
     * Here in its simplest form: a poll ran and did not mark us level, so there is nothing vouching for the
     * members view. Asserted with everything else satisfied, so the only thing that can decline it is this
     * guard. [V25e][`V25e - a level mark from an earlier poll does not authorise this poll's rekey`] covers
     * the harder half, where a mark exists but belongs to an earlier poll.
     */
    @Test
    fun `V25d - a members view nothing vouches for blocks the rekey`() {
        givenGroup(admin = true)

        assertFalse(rekey(forceRekey.beginPoll(groupId.hexString)))

        verify(exactly = 0) { configs.rekey() }
    }

    /**
     * V25e — the level mark must belong to the poll that is asking.
     *
     * This is the hazard the token exists for, and the one a boolean-or-presence check cannot see. A device
     * last fully level yesterday, offered a members update since that it hasn't merged, still *has* a mark:
     * presence says yes, and the rekey would go out against a view that is a day and one member stale. Only
     * the poll it was recorded by tells those apart.
     *
     * Asserting on the rekey count throughout, because every refusal here is indistinguishable from doing
     * nothing.
     */
    @Test
    fun `V25e - a level mark from an earlier poll does not authorise this poll's rekey`() {
        givenGroup(admin = true)

        // A poll that marked us level: proceeds. Its token is kept, which is the point of the test — a
        // caller that holds on to one is exactly what the currency check has to answer.
        val levelPoll = completedPoll()
        assertTrue(rekey(levelPoll))
        verify(exactly = 1) { configs.rekey() }

        // A day on, so the storm guard is not what answers, and a fresh poll that has marked nothing.
        now += 25 * 60 * 60 * 1000L
        val stalePoll = forceRekey.beginPoll(groupId.hexString)

        // The earlier poll's OWN token is not a way back in. This is the step that separates "is the mark
        // from the poll you name" from "is that poll still current": the mark `levelPoll` left is still in
        // the map, so the first question says yes and only the second refuses. A caller who kept a token
        // would otherwise be told it is level now on information of any age.
        assertFalse(rekey(levelPoll))
        verify(exactly = 1) { configs.rekey() }

        // The mark must still be PRESENT here, and asserting that is what stops this test passing for the
        // wrong reason. A later change that folded the poll check into the withdrawal would make both
        // readings go false together: the rekey below would still refuse, this test would still pass, and
        // the re-store path would have been silently broken instead.
        assertTrue(forceRekey.localStateIsLevelWithSwarm(groupId.hexString))

        assertFalse(rekey(stalePoll))
        verify(exactly = 1) { configs.rekey() }

        // Reachability control at the same clock: a mark from the current poll still gets through, so the
        // refusal above was the token's doing and not some state the first rekey left behind.
        assertTrue(rekey(completedPoll()))
        verify(exactly = 2) { configs.rekey() }
    }

    /** Only a backfill that failed across the whole swarm permits a rekey, whatever else is true. */
    @Test
    fun `no rekey unless the backfill failed`() {
        givenGroup(admin = true)

        for (outcome in listOf(KeysBackfill.NotAttempted, KeysBackfill.Captured, KeysBackfill.Inconclusive)) {
            assertFalse(rekey(backfill = outcome))
        }

        verify(exactly = 0) { configs.rekey() }
    }

    /**
     * V25f — one instance serves every group and the user's own account, so other swarms poll constantly
     * in between. Our mark must survive that.
     *
     * The mistake this pins is a token held as a single shared "current poll" value rather than one each
     * caller carries: any other swarm's poll would then bump it, our own mark would read stale moments
     * after being made, and the rekey would never fire again. It fails CLOSED, so nothing errors and no
     * other test here notices — the feature just quietly stops existing.
     */
    @Test
    fun `V25f - another swarm polling does not make our own mark stale`() {
        givenGroup(admin = true)

        val ours = completedPoll()

        // A whole poll of a DIFFERENT swarm, begun and marked, in between. Its key is what makes this a
        // test of cross-swarm interference rather than of our own poll being superseded.
        val otherSwarm = AccountId(IdPrefix.GROUP, ByteArray(32) { 8 }).hexString
        forceRekey.markLocalStateLevelWithSwarm(
            swarmPubKeyHex = otherSwarm,
            pollToken = forceRekey.beginPoll(otherSwarm),
            mergedConfigMessagesForDiagnosticsOnly = true,
        )

        assertTrue(rekey(ours))
        verify(exactly = 1) { configs.rekey() }
    }

    /**
     * The withdrawal has to clear the mark for both readings, not just the one it was written for.
     * It removes the map entry, which is a different operation now that the value is a token rather than
     * set membership — so it is checked rather than assumed.
     */
    @Test
    fun `a withdrawn swarm is level by neither reading`() {
        givenGroup(admin = true)

        val token = completedPoll()
        forceRekey.markMergeIncompleteForSwarm(groupId.hexString)

        assertFalse(forceRekey.localStateIsLevelWithSwarm(groupId.hexString))
        assertFalse(rekey(token))
        verify(exactly = 0) { configs.rekey() }
    }

    /**
     * An admin that holds the keys bytes, whose one re-store of them fails, must not rekey.
     *
     * The failed re-store is what makes this reachable. The poll reports the group expired on it, and that
     * report together with a backfill in the same poll is all the caller checks, so the rekey has to
     * notice the held bytes for itself. The backfill runs because some other active keys hash has no bytes
     * behind it, so holding some bytes and having a backfill attempted are true together.
     *
     * The round is the production one, with its store failing. The retained bytes are in the shape the
     * wrapper's `activeKeyMessages()` returns, and the restore is the one ConfigRestoreSource builds from
     * them (pinned in [ConfigRestoreSourceTest]). The restore source is stubbed rather than real because
     * its keys restore resolves the native namespace.
     */
    @Test
    fun `an admin holding the keys bytes does not rekey after one failed re-store`() = runTest {
        givenGroup(admin = true)
        // Bytes for one keys hash and not the other, and no node holds the other: the backfill fails
        // across the whole swarm while this device still holds bytes it could re-store.
        activeKeysHashes = listOf("keys-1", "keys-2")
        retained["keys-1"] = "keys-one".toByteArray()
        storesFail = true

        val restoreSource = mockk<ConfigRestoreSource>()
        every { restoreSource.canRepairGroupKeys(groupId, any()) } returns true
        every { restoreSource.groupConfigsToRestore(groupId, any()) } returns listOf(
            PendingRestore(
                label = "group keys for $groupId",
                push = ConfigPush(retained.values.map { Bytes(it) }, 0L, emptyList()),
                claimedHashes = retained.keys.toSet(),
                isGroupKeys = true,
                namespace = { GROUP_KEYS_NAMESPACE },
            )
        )
        val appVisibilityManager = mockk<AppVisibilityManager>()
        every { appVisibilityManager.isAppVisible } returns MutableStateFlow(true)
        forceRekey = recovery(restoreSource = restoreSource, appVisibilityManager = appVisibilityManager)
        val pollToken = completedPoll()

        val outcome = backfill()
        assertEquals(KeysBackfill.Failed, outcome)

        val groupExpired = groupExpiredAfterPoll(
            noKeysAfterMerge = false,
            report = everyKeysHashMissing,
            keysHashes = keysHashes,
            canRepairKeys = { forceRekey.canRepairGroupKeys(groupId, keysHashes) },
            runRecoveryRound = { report ->
                forceRekey.onGroupConfigsChecked(groupId, authFor(groupId), report)
            },
        )
        assertEquals(true, groupExpired, "the failed re-store must report the group expired for this to test anything")

        assertFalse(rekeyAfter(outcome, pollToken))
        verify(exactly = 0) { configs.rekey() }

        // Reachability control: the same instance and poll, with the bytes gone, does rekey.
        retained.clear()
        assertTrue(rekeyAfter(outcome, pollToken))
        verify(exactly = 1) { configs.rekey() }
    }

    /**
     * The rekey runs inside the poll, and anything that escapes it is rethrown when the poll finishes, which
     * fails a poll whose merge and re-stores had already succeeded.
     */
    @Test
    fun `a rekey that throws does not escape`() {
        givenGroup(admin = true)
        every { configs.rekey() } throws RuntimeException("libsession: C++ exception")

        assertFalse(rekey())
        verify(exactly = 1) { configs.rekey() }
    }

    /**
     * A rekey attempt from a poll that has marked us level, after a backfill that failed across the swarm and
     * an expiry check saying every keys hash is gone, unless told otherwise.
     */
    private fun rekey(
        pollToken: PollToken = completedPoll(),
        backfill: KeysBackfill = KeysBackfill.Failed,
        report: ConfigExpiryReport? = everyKeysHashMissing,
    ) = forceRekey.rekeyIfUnrecoverable(
        groupId = groupId,
        backfill = backfill,
        report = report,
        keysHashes = keysHashes,
        pollToken = pollToken,
    )

    /** The rekey as the poll makes it, after a backfill with [outcome]. */
    private fun rekeyAfter(outcome: KeysBackfill, pollToken: PollToken) =
        rekey(pollToken = pollToken, backfill = outcome)

    /** The poll's backfill, whose first node is the poll's own. */
    private suspend fun backfill() = forceRekey.backfillIfNeeded(groupId, authFor(groupId), nodeA)

    private fun recovery(
        restoreSource: ConfigRestoreSource = mockk(relaxed = true),
        appVisibilityManager: AppVisibilityManager = mockk(relaxed = true),
    ) = ExpiredConfigRecovery(
        restoreSource = restoreSource,
        clock = clock,
        appVisibilityManager = appVisibilityManager,
        swarmApiExecutor = swarmApiExecutor,
        storeMessageApiFactory = mockk(relaxed = true),
        deleteMessageApiFactory = mockk(relaxed = true),
        retrieveMessageFactory = mockk(relaxed = true),
        configFactory = configFactory,
        swarmDirectory = swarmDirectory,
    ).also {
        // Hardcoded rather than read from libsession's native Namespace, which unit tests cannot load.
        it.keysNamespace = { GROUP_KEYS_NAMESPACE }
    }

    private fun givenFetchedSwarm(unreadable: Int) {
        coEvery { swarmDirectory.fetchSwarmCounted(groupId.hexString) } returns
                SwarmDirectory.FetchedSwarm(listOf(nodeA, nodeB, nodeC), unreadable = unreadable)
    }

    private fun node(name: String) = Snode("https://$name", 443, Snode.KeySet("ed-$name", "x-$name"))

    /**
     * Mocked rather than constructed: `Message.data` lazily decodes `dataB64` through
     * `android.util.Base64`, which is not available to a JVM unit test.
     */
    private fun message(hash: String) = mockk<RetrieveMessageResponse.Message>().also {
        every { it.hash } returns hash
        every { it.data } returns "keys-message-bytes".toByteArray()
        every { it.timestamp } returns Instant.EPOCH
    }

    /** A poll that ran and took everything in: mints the token and marks the swarm level with it. */
    private fun completedPoll(): PollToken {
        val token = forceRekey.beginPoll(groupId.hexString)
        forceRekey.markLocalStateLevelWithSwarm(
            swarmPubKeyHex = groupId.hexString,
            pollToken = token,
            mergedConfigMessagesForDiagnosticsOnly = true,
        )
        return token
    }

    private fun authFor(swarm: AccountId): SwarmAuth = mockk<SwarmAuth>().also {
        every { it.accountId } returns swarm
    }

    private fun givenGroup(admin: Boolean, kicked: Boolean = false, destroyed: Boolean = false) {
        val userGroups = mockk<ReadableUserGroupsConfig>()
        every { userGroups.getClosedGroup(groupId.hexString) } returns GroupInfo.ClosedGroupInfo(
            groupAccountId = groupId.hexString,
            adminKey = if (admin) Bytes(ByteArray(64) { 7 }) else null,
            authData = if (admin) null else Bytes(ByteArray(100) { 4 }),
            priority = 0L,
            invited = false,
            name = "A group",
            kicked = kicked,
            destroyed = destroyed,
            joinedAtSecs = 0L,
        )

        val userConfigs = mockk<UserConfigs>()
        every { userConfigs.userGroups } returns userGroups
        every { configFactory.dangerouslyAccessUserConfigs() } returns (userConfigs to {})
    }

    private companion object {
        /** Hardcoded rather than read from libsession's native `Namespace`, which unit tests can't load. */
        const val GROUP_KEYS_NAMESPACE = 12
    }
}

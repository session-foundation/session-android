package org.thoughtcrime.securesms.configs

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Instant
import network.loki.messenger.libsession_util.ReadableGroupKeysConfig
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.GroupConfigs
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.RetrieveMessageApi
import org.thoughtcrime.securesms.api.snode.StoreMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.util.MockLoggingRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Re-loading a group's keys messages so libsession captures their bytes.
 *
 * The trigger under test is bytes-absent, not keys-related — a distinction V24b exists to pin, because a
 * fixture that fires the backfill unconditionally still passes V24.
 *
 * Note on V14: the empty-ask guard cannot decide anything here. It governs the *expiry detection* request,
 * and this path never builds one — the backfill is a namespace re-poll driven by what we hold, not by what
 * a swarm reported. The caution about V24 needing other configs' hashes applies to a detection-mediated
 * fixture; these exercise the component directly.
 */
class KeysBackfillTest {
    @get:Rule
    val loggingRule = MockLoggingRule()

    private val groupId = AccountId(IdPrefix.GROUP, ByteArray(32) { 2 })
    /** The poll's own node, which the backfill asks first, and the rest of the swarm. */
    private val snode = Snode("https://a", 443, Snode.KeySet("ed-a", "x-a"))
    private val otherNode = Snode("https://b", 443, Snode.KeySet("ed-b", "x-b"))

    /** What every node holds for the keys namespace. */
    private var swarmHolds: List<RetrieveMessageResponse.Message> = emptyList()

    private var activeHashes = listOf<String>()
    private val held = mutableMapOf<String, ByteArray>()

    private lateinit var configFactory: ConfigFactoryProtocol
    private lateinit var swarmApiExecutor: SwarmApiExecutor
    private lateinit var retrieveFactory: RetrieveMessageApi.Factory
    private lateinit var backfill: ExpiredConfigRecovery
    private var now = 1_000_000L

    private companion object { const val GROUP_KEYS_NAMESPACE = 12 }

    @Before
    fun setUp() {
        configFactory = mockk(relaxed = true)
        swarmApiExecutor = mockk()
        retrieveFactory = mockk(relaxed = true)

        val clock = mockk<SnodeClock>()
        every { clock.currentTimeMillis() } answers { now }

        swarmHolds = listOf(message("keys-1"))
        coEvery { swarmApiExecutor.send(any(), any()) } answers { RetrieveMessageResponse(messages = swarmHolds) }

        // A merge retains the bytes of every keys message it loads, which is what libsession's retention
        // does. Without that, no fetch would ever be seen to capture anything.
        every { configFactory.mergeGroupConfigMessages(groupId, any(), any(), any()) } answers {
            val keys = secondArg<List<ConfigMessage>>()
            keys.forEach { held[it.hash] = it.data }
            keys.size
        }
        val keys = mockk<ReadableGroupKeysConfig>(relaxed = true)
        every { keys.activeHashes() } answers { activeHashes }
        every { keys.activeKeyMessages() } answers { held.toMap() }
        val configs = mockk<GroupConfigs>(relaxed = true)
        every { configs.groupKeys } returns keys
        every { configFactory.dangerouslyAccessGroupConfigs(groupId) } returns (configs to {})

        val swarmDirectory = mockk<SwarmDirectory>()
        coEvery { swarmDirectory.fetchSwarmCounted(groupId.hexString) } returns
                SwarmDirectory.FetchedSwarm(listOf(snode, otherNode), unreadable = 0)

        backfill = ExpiredConfigRecovery(
            restoreSource = mockk(relaxed = true),
            clock = clock,
            appVisibilityManager = mockk(relaxed = true),
            swarmApiExecutor = swarmApiExecutor,
            storeMessageApiFactory = mockk(relaxed = true),
            deleteMessageApiFactory = mockk(relaxed = true),
            retrieveMessageFactory = retrieveFactory,
            configFactory = configFactory,
            swarmDirectory = swarmDirectory,
        ).also {
            // Hardcoded rather than read from libsession's native Namespace, which unit tests cannot load.
            it.keysNamespace = { GROUP_KEYS_NAMESPACE }
        }
    }

    /** V24 — a hash we hold with no bytes behind it triggers a re-poll, and the result is merged. */
    @Test
    fun `V24 - an active keys hash with no bytes triggers a re-poll`() = runTest {
        givenKeys(activeHashes = listOf("keys-1"), heldBytes = emptyMap())

        assertEquals(KeysBackfill.Captured, backfill.backfillIfNeeded(groupId, auth(), snode))

        // The poll's own node had it, so nobody else is asked.
        coVerify(exactly = 1) { swarmApiExecutor.send(any(), any()) }
        assertTrue("keys-1" in held)
        // Merged through the ordinary path — that merge is a no-op for key state and is precisely what
        // records the bytes.
        coVerify(exactly = 1) {
            configFactory.mergeGroupConfigMessages(groupId, any(), emptyList(), emptyList())
        }
    }

    /**
     * V24b — bytes already held means no fetch is issued at all.
     *
     * The one most easily built vacuously: a fixture that fires the backfill regardless still satisfies V24,
     * so this asserts the *absence* of the request rather than the presence of a result. The reachability
     * control below is what stops "no fetch" being produced equally by the component never running.
     */
    @Test
    fun `V24b - bytes already held issues no fetch at all`() = runTest {
        givenKeys(activeHashes = listOf("keys-1"), heldBytes = mapOf("keys-1" to bytes()))

        assertEquals(KeysBackfill.NotAttempted, backfill.backfillIfNeeded(groupId, auth(), snode))

        coVerify(exactly = 0) { swarmApiExecutor.send(any(), any()) }

        // Reachability control: the same instance, through the same fixture, must still fetch when a hash
        // genuinely lacks bytes — otherwise "no fetch" proves nothing about the trigger.
        givenKeys(activeHashes = listOf("keys-1", "keys-2"), heldBytes = mapOf("keys-1" to bytes()))
        swarmHolds = listOf(message("keys-2"))
        assertEquals(KeysBackfill.Captured, backfill.backfillIfNeeded(groupId, auth(), snode))
        coVerify(exactly = 1) { swarmApiExecutor.send(any(), any()) }
    }

    /**
     * V24a — no node of the swarm holds the message and every node answered: the attempt fails, and it is
     * recorded, so a group whose keys are genuinely gone stops re-polling.
     *
     * Without this a group the swarm can no longer help re-polls its namespace on every poll, forever: the
     * bytes never arrive, so the trigger never clears on its own.
     */
    @Test
    fun `V24a - a recorded attempt stops a second re-poll within the bar`() = runTest {
        givenKeys(activeHashes = listOf("keys-1"), heldBytes = emptyMap())
        // The swarm no longer holds it, so the condition cannot self-clear.
        swarmHolds = emptyList()

        assertEquals(KeysBackfill.Failed, backfill.backfillIfNeeded(groupId, auth(), snode))
        assertEquals(KeysBackfill.NotAttempted, backfill.backfillIfNeeded(groupId, auth(), snode))
        assertEquals(KeysBackfill.NotAttempted, backfill.backfillIfNeeded(groupId, auth(), snode))

        // One request per node of the swarm, once.
        coVerify(exactly = 2) { swarmApiExecutor.send(any(), any()) }

        // ...and the bar is a bar, not a permanent block: past it, one more attempt is allowed.
        now += 61 * 60 * 1000L
        assertEquals(KeysBackfill.Failed, backfill.backfillIfNeeded(groupId, auth(), snode))
        coVerify(exactly = 4) { swarmApiExecutor.send(any(), any()) }
    }

    /**
     * V24c — the backfill FEEDS recovery, it does not duplicate it.
     *
     * It restores the input a re-store needs and stores nothing itself. A backfill that also re-stored would
     * be the V23 path built a second time, with two implementations of one rule to keep in step.
     */
    @Test
    fun `V24c - the backfill stores nothing of its own`() = runTest {
        givenKeys(activeHashes = listOf("keys-1"), heldBytes = emptyMap())

        backfill.backfillIfNeeded(groupId, auth(), snode)

        // Asserted on the API TYPE, not on a count: the backfill issues exactly one request and it is a
        // retrieve. A count alone would pass for an implementation that stored instead of fetching.
        coVerify(exactly = 0) { swarmApiExecutor.send(any(), match { it.api is StoreMessageApi }) }
        coVerify(exactly = 1) { swarmApiExecutor.send(any(), match { it.api is RetrieveMessageApi }) }
    }

    private fun givenKeys(activeHashes: List<String>, heldBytes: Map<String, ByteArray>) {
        this.activeHashes = activeHashes
        held.clear()
        held.putAll(heldBytes)
    }

    /**
     * Mocked rather than constructed: `Message.data` lazily decodes `dataB64` through
     * `android.util.Base64`, which is not available to a JVM unit test — a real instance fails on access
     * with "Method decode not mocked", nowhere near the assertion.
     */
    private fun message(hash: String) = mockk<RetrieveMessageResponse.Message>().also {
        every { it.hash } returns hash
        every { it.data } returns "keys-message-bytes".toByteArray()
        every { it.timestamp } returns Instant.EPOCH
    }

    private fun bytes() = "keys-bytes".toByteArray()

    private fun auth(): SwarmAuth = mockk<SwarmAuth>(relaxed = true).also {
        every { it.accountId } returns groupId
    }
}

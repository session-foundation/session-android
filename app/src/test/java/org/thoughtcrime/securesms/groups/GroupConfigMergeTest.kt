package org.thoughtcrime.securesms.groups

import io.mockk.every
import io.mockk.mockk
import network.loki.messenger.libsession_util.MutableGroupKeysConfig
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.session.libsession.utilities.ConfigMessage
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.configs.ExpiredConfigRecovery
import org.thoughtcrime.securesms.dependencies.loadKeyMessages
import org.thoughtcrime.securesms.util.MockLoggingRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Whether a group poll's config merge leaves this device level with the swarm, driven through the functions
 * the poller calls: [mergeGroupConfigs] for the decision and [ExpiredConfigRecovery.recordConfigMerge] to
 * record it.
 *
 * The keys half of each merge goes through the production [loadKeyMessages], with `loadKey` answering as
 * libsession does. `SupplementalKeysLoadTest` asserts those answers against the real library; info and members
 * report every message merged unless a test says otherwise.
 */
class GroupConfigMergeTest {
    @get:Rule
    val loggingRule = MockLoggingRule()

    private val groupId = AccountId(IdPrefix.GROUP, ByteArray(32) { 5 })
    private val swarm = groupId.hexString

    private val full = ConfigMessage("full-1", "full".toByteArray(), 1L)
    private val supplemental = ConfigMessage("supplemental-1", "supplemental".toByteArray(), 2L)
    private val info = ConfigMessage("info-1", "info".toByteArray(), 3L)
    private val members = ConfigMessage("members-1", "members".toByteArray(), 4L)

    private lateinit var keys: MutableGroupKeysConfig
    private lateinit var recovery: ExpiredConfigRecovery

    @Before
    fun setUp() {
        keys = mockk()
        every { keys.loadKey(full.data, full.hash, full.timestamp) } returns true
        // A valid supplemental that gives this device no key, which libsession answers with false.
        every { keys.loadKey(supplemental.data, supplemental.hash, supplemental.timestamp) } returns false

        recovery = ExpiredConfigRecovery(
            restoreSource = mockk(relaxed = true),
            clock = mockk(relaxed = true),
            appVisibilityManager = mockk(relaxed = true),
            swarmApiExecutor = mockk(relaxed = true),
            storeMessageApiFactory = mockk(relaxed = true),
            deleteMessageApiFactory = mockk(relaxed = true),
            retrieveMessageFactory = mockk(relaxed = true),
            configFactory = mockk(relaxed = true),
            swarmDirectory = mockk(relaxed = true),
        )
    }

    /**
     * A poll that fetches a supplemental leaves the swarm level. It is taken in, though it gave this device no
     * key, so adding a member with history does not withdraw the group from recovery.
     */
    @Test
    fun `a poll that fetched a supplemental leaves the swarm level`() {
        val count = merge(keys = listOf(full, supplemental))

        assertEquals(4, count.given)
        assertEquals(4, count.merged)
        record(count)
        assertTrue(recovery.localStateIsLevelWithSwarm(swarm))
    }

    /** V22c — a merge that skipped a message it could not take in does not leave the swarm level. */
    @Test
    fun `V22c - a merge that took in only some of what was fetched does not leave the swarm level`() {
        // Level from an earlier poll, so that the result below is this merge's doing.
        record(merge(keys = listOf(full)))
        assertTrue(recovery.localStateIsLevelWithSwarm(swarm))

        val count = merge(keys = listOf(full, supplemental), infoTakenIn = 0)

        assertFalse(count.tookEverythingIn)
        record(count)
        assertFalse(recovery.localStateIsLevelWithSwarm(swarm))
    }

    /**
     * A poll that fetched nothing leaves the swarm level, without merging: the swarm holds nothing we lack.
     * This is the poll a group whose configs have expired makes, so gating the mark on having merged
     * something would make recovery unreachable for it.
     */
    @Test
    fun `a poll that fetched nothing leaves the swarm level without merging`() {
        val count = mergeGroupConfigs(emptyList(), emptyList(), emptyList()) { _, _, _ ->
            fail("nothing was fetched, so there is nothing to merge")
        }

        record(count)
        assertTrue(recovery.localStateIsLevelWithSwarm(swarm))
    }

    /** The poll's merge, with the keys loaded through [loadKeyMessages]. */
    private fun merge(keys: List<ConfigMessage>, infoTakenIn: Int = 1) =
        mergeGroupConfigs(keys, listOf(info), listOf(members)) { k, _, _ ->
            this.keys.loadKeyMessages(k).takenIn + infoTakenIn + 1
        }

    private fun record(count: ConfigMergeCount) = recovery.recordConfigMerge(
        swarmPubKeyHex = swarm,
        pollToken = recovery.beginPoll(swarm),
        tookEverythingIn = count.tookEverythingIn,
        mergedConfigMessagesForDiagnosticsOnly = count.given > 0,
    )
}

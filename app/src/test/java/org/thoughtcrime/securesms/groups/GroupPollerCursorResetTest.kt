package org.thoughtcrime.securesms.groups

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.GroupInfo
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.GroupConfigs
import org.session.libsession.utilities.UserConfigs
import org.session.libsignal.database.LastMessageHashEpoch
import org.session.libsignal.database.LastMessageHashResets
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.RetrieveMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.api.swarm.SwarmSnodeSelector
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.MockLoggingRule
import org.thoughtcrime.securesms.util.NetworkConnectivity
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A group's cursors reset while a poll of that group is in flight: the poll must not write its position
 * back, so the next poll fetches from the beginning.
 *
 * Driven through [GroupPoller.manualPollOnce], the real poll, with the group messages retrieve held open
 * while the reset happens. The cursor store is a fake that uses the production [LastMessageHashResets] in
 * the same way LokiAPIDatabase does, since the real one needs SQLCipher.
 */
class GroupPollerCursorResetTest {
    @get:Rule
    val loggingRule = MockLoggingRule()

    private val groupId = AccountId(IdPrefix.GROUP, ByteArray(32) { 6 })
    private val snode = Snode("https://a", 443, Snode.KeySet("ed-a", "x-a"))

    private val cursors = FakeCursors()
    private val messagesRequested = CompletableDeferred<Unit>()
    private val releaseMessages = CompletableDeferred<Unit>()
    private lateinit var poller: GroupPoller

    @Before
    fun setUp() {
        val configFactory = mockk<ConfigFactoryProtocol>(relaxed = true)
        every { configFactory.getGroupAuth(groupId) } returns mockk<SwarmAuth>(relaxed = true)
        val groupConfigs = mockk<GroupConfigs>(relaxed = true)
        every { groupConfigs.groupKeys.activeHashes() } returns emptyList()
        every { groupConfigs.groupInfo.activeHashes() } returns emptyList()
        every { groupConfigs.groupMembers.activeHashes() } returns emptyList()
        every { configFactory.dangerouslyAccessGroupConfigs(groupId) } returns (groupConfigs to {})
        val userGroups = mockk<ReadableUserGroupsConfig>()
        every { userGroups.getClosedGroup(groupId.hexString) } returns GroupInfo.ClosedGroupInfo(
            groupAccountId = groupId.hexString,
            adminKey = null,
            authData = Bytes(ByteArray(100) { 4 }),
            priority = 0L,
            invited = false,
            name = "A group",
            kicked = false,
            destroyed = false,
            joinedAtSecs = 0L,
        )
        val userConfigs = mockk<UserConfigs>()
        every { userConfigs.userGroups } returns userGroups
        every { configFactory.dangerouslyAccessUserConfigs() } returns (userConfigs to {})

        // Each retrieve is tagged with its namespace, so the executor can answer by namespace.
        val namespaceOf = HashMap<RetrieveMessageApi, Int>()
        val retrieveFactory = mockk<RetrieveMessageApi.Factory>()
        every { retrieveFactory.create(any(), any(), any(), any()) } answers {
            mockk<RetrieveMessageApi>().also { synchronized(namespaceOf) { namespaceOf[it] = firstArg() } }
        }
        val swarmApiExecutor = mockk<SwarmApiExecutor>()
        coEvery { swarmApiExecutor.send(any(), any()) } coAnswers {
            val namespace = synchronized(namespaceOf) { namespaceOf.getValue(secondArg<SwarmApiRequest<*>>().api as RetrieveMessageApi) }
            if (namespace == Namespaces.messages) {
                messagesRequested.complete(Unit)
                releaseMessages.await()
                RetrieveMessageResponse(listOf(RetrieveMessageResponse.Message(hash = "m1", t1 = Instant.ofEpochMilli(1))))
            } else {
                RetrieveMessageResponse(emptyList())
            }
        }
        val snodeSelector = mockk<SwarmSnodeSelector>()
        coEvery { snodeSelector.selectSnode(groupId.hexString) } returns snode

        // Never visible and never online, so the poller's own loop never starts a routine poll; the only
        // poll is the one each test asks for.
        val networkConnectivity = mockk<NetworkConnectivity>()
        every { networkConnectivity.networkAvailable } returns MutableStateFlow(false)
        val appVisibilityManager = mockk<AppVisibilityManager>()
        every { appVisibilityManager.isAppVisible } returns MutableStateFlow(false)

        poller = GroupPoller(
            groupId = groupId,
            pollSemaphore = Semaphore(1),
            configFactoryProtocol = configFactory,
            lokiApiDatabase = cursors,
            clock = mockk(relaxed = true),
            groupRevokedMessageHandler = mockk(relaxed = true),
            receivedMessageHashDatabase = mockk(relaxed = true),
            messageParser = mockk(relaxed = true),
            receivedMessageProcessor = mockk(relaxed = true),
            retrieveMessageFactory = retrieveFactory,
            alterTtlApiApiFactory = mockk(relaxed = true),
            swarmApiExecutor = swarmApiExecutor,
            swarmSnodeSelector = snodeSelector,
            networkConnectivity = networkConnectivity,
            appVisibilityManager = appVisibilityManager,
        ).also { it.namespaces = Namespaces }
    }

    @After
    fun tearDown() {
        poller.cancel()
    }

    /** Control: with no reset, the poll writes its position as it always has. */
    @Test
    fun `with no reset the poll writes its cursor`() = runBlocking {
        cursors.seed(Namespaces.messages, "old")

        pollWhileHeld { }

        assertEquals("m1", cursors.messagesCursor())
    }

    @Test
    fun `a reset while the poll is in flight is not undone by it`() = runBlocking {
        cursors.seed(Namespaces.messages, "old")

        pollWhileHeld { cursors.clearLastMessageHashes(groupId.hexString) }

        assertNull(cursors.messagesCursor())
    }

    /**
     * The cursor was already empty when the poll started, so it reads the same before and after the reset.
     * Only a guard that counts resets, rather than comparing values, sees that one happened.
     */
    @Test
    fun `a reset of a cursor that was already empty is not undone`() = runBlocking {
        pollWhileHeld { cursors.clearLastMessageHashes(groupId.hexString) }

        assertNull(cursors.messagesCursor())
    }

    /**
     * A reset arriving while the poll's cursor write is under way lands after it, not between the write's
     * check and the write. The reset is started from inside the write and given time to run; it has to wait
     * for the write, so it clears what the write stored.
     */
    @Test
    fun `a reset during the cursor write is not undone`() = runBlocking {
        cursors.seed(Namespaces.messages, "old")
        val resetting = CountDownLatch(1)
        var reset: Thread? = null
        cursors.duringMessagesWrite = {
            reset = thread {
                resetting.countDown()
                cursors.clearLastMessageHashes(groupId.hexString)
            }
            resetting.await(5, TimeUnit.SECONDS)
            Thread.sleep(200)
        }

        pollWhileHeld { }
        reset!!.join(5_000)

        assertTrue(cursors.resets > 0, "the reset must have run for this to test anything")
        assertNull(cursors.messagesCursor())
    }

    /** Starts a poll, runs [whileInFlight] once its group messages retrieve is open, then lets it finish. */
    private suspend fun pollWhileHeld(whileInFlight: () -> Unit) = coroutineScope {
        val poll = async(Dispatchers.Default) { poller.manualPollOnce() }
        withTimeout(5_000) { messagesRequested.await() }
        whileInFlight()
        releaseMessages.complete(Unit)
        withTimeout(5_000) { poll.await() }
    }

    private object Namespaces : GroupNamespaces {
        override val keys = 12
        override val info = 13
        override val members = 14
        override val messages = 11
        override val revokedMessages = 16
    }

    /** Cursors in memory, guarded by [LastMessageHashResets] exactly as LokiAPIDatabase guards its table. */
    private inner class FakeCursors(
        rest: LokiAPIDatabaseProtocol = mockk(relaxed = true),
    ) : LokiAPIDatabaseProtocol by rest {
        private val guard = LastMessageHashResets()
        private val stored = HashMap<Pair<String, Int>, String>()
        var duringMessagesWrite: (() -> Unit)? = null
        @Volatile var resets = 0

        fun seed(namespace: Int, value: String) {
            synchronized(stored) { stored[groupId.hexString to namespace] = value }
        }

        fun messagesCursor(): String? = synchronized(stored) { stored[groupId.hexString to Namespaces.messages] }

        override fun getLastMessageHashValue(snode: Snode, publicKey: String, namespace: Int): String? =
            synchronized(stored) { stored[publicKey to namespace] }

        override fun lastMessageHashEpoch(): LastMessageHashEpoch = guard.epoch()

        override fun setLastMessageHashValue(
            snode: Snode,
            publicKey: String,
            newValue: String,
            namespace: Int,
            since: LastMessageHashEpoch,
        ): Boolean = guard.writeUnlessResetSince(publicKey, since) {
            if (namespace == Namespaces.messages) duringMessagesWrite?.invoke()
            synchronized(stored) { stored[publicKey to namespace] = newValue }
        }

        override fun clearLastMessageHashes(publicKey: String) = guard.reset(publicKey) {
            resets++
            synchronized(stored) { stored.keys.removeAll { it.first == publicKey } }
        }
    }
}

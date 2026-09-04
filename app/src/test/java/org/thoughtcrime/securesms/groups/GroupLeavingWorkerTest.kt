package org.thoughtcrime.securesms.groups

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.ReadableGroupMembersConfig
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.util.GroupInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.session.libsession.messaging.groups.GroupScope
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.GroupConfigs
import org.session.libsession.utilities.UserConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.NoOpLogger
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.ConfigFactory

class GroupLeavingWorkerTest {
    private val groupId = AccountId("03${"11".repeat(32)}")

    private val storage = mockk<Storage>(relaxed = true)
    private val configFactory = mockk<ConfigFactory>(relaxed = true)
    private val messageSender = mockk<MessageSender>()

    @Before
    fun setUp() {
        Log.initialize(NoOpLogger)

        val group = mockk<GroupInfo.ClosedGroupInfo> {
            every { kicked } returns false
            every { destroyed } returns false
        }

        every { configFactory.dangerouslyAccessUserConfigs() } returns Pair(
            mockk<UserConfigs> {
                every { userGroups } returns mockk<ReadableUserGroupsConfig> {
                    every { getClosedGroup(groupId.hexString) } returns group
                }
            },
            {},
        )

        // No admins, so we are not the only admin and the leave takes the announce-and-go path
        every { configFactory.dangerouslyAccessGroupConfigs(groupId) } returns Pair(
            mockk<GroupConfigs> {
                every { groupMembers } returns mockk<ReadableGroupMembersConfig> {
                    every { all() } returns emptyList()
                }
            },
            {},
        )
    }

    @Test
    fun `group is left locally when the departure cannot be announced`() = runTest {
        answerSendWith(Result.failure(NonRetryableException("no keys for this group")))

        val result = worker(runAttemptCount = 0).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify { configFactory.removeGroup(groupId) }
        verify(exactly = 0) { storage.insertGroupInfoErrorQuit(any()) }
    }

    @Test
    fun `retries stop once the attempts are used up`() = runTest {
        answerSendWith(Result.failure(RuntimeException("network went away")))

        assertEquals(ListenableWorker.Result.retry(), worker(runAttemptCount = 0).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(runAttemptCount = 2).doWork())
    }

    @Test
    fun `the error message replaces the one from the previous attempt`() = runTest {
        answerSendWith(Result.failure(RuntimeException("network went away")))

        worker(runAttemptCount = 0).doWork()

        verify(exactly = 1) { storage.insertGroupInfoErrorQuit(groupId) }
        verify {
            storage.deleteGroupInfoMessages(groupId, UpdateMessageData.Kind.GroupErrorQuit::class.java)
        }
    }

    /**
     * The status channel is a rendezvous one, so a result can only be handed over while the worker
     * is waiting for it — and the worker stops waiting after the first failure, leaving the second
     * send parked on the channel for the background scope to cancel.
     */
    private fun TestScope.answerSendWith(result: Result<Unit>) {
        every { messageSender.send(any(), any(), any()) } answers {
            val statusChannel = thirdArg<SendChannel<Result<Unit>>>()
            backgroundScope.launch { statusChannel.send(result) }
        }
    }

    private fun CoroutineScope.worker(runAttemptCount: Int) = GroupLeavingWorker(
        context = mockk(relaxed = true),
        params = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns Data.Builder()
                .putString("group_id", groupId.hexString)
                .build()
            every { this@mockk.runAttemptCount } returns runAttemptCount
        },
        storage = storage,
        configFactory = configFactory,
        groupScope = GroupScope(this),
        tokenFetcher = mockk { every { token } returns MutableStateFlow(null) },
        serverApiExecutor = mockk(relaxed = true),
        pushUnregisterApiFactory = mockk(relaxed = true),
        messageSender = messageSender,
    )
}

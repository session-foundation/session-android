package org.session.libsession.messaging.jobs

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.ReadableGroupKeysConfig
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.GroupConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.session.protos.SessionProtos
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.NoOpLogger
import kotlin.time.Duration.Companion.seconds

class MessageSendJobTest {
    private val groupId = "03${"11".repeat(32)}"

    @Before
    fun setUp() {
        Log.initialize(NoOpLogger)
    }

    @Test
    fun `sending to a group we hold no keys for fails non-retryably`() = runTest {
        val statusChannel = Channel<Result<Unit>>(capacity = 1)

        job(statusChannel, groupKeys = emptyList()).execute("test")

        val error = statusChannel.receive().exceptionOrNull()
        assertTrue("expected NonRetryableException, got $error", error is NonRetryableException)
    }

    @Test
    fun `sending waits for keys that arrive while it is waiting`() = runTest {
        val statusChannel = Channel<Result<Unit>>(capacity = 1)
        val keys = MutableStateFlow(emptyList<ByteArray>())
        val configUpdates = MutableSharedFlow<ConfigUpdateNotification>()

        launch {
            // Well inside the 20s the send is prepared to wait, and virtual time, so it is free
            delay(5.seconds)
            keys.value = listOf(ByteArray(32))
            configUpdates.emit(ConfigUpdateNotification.GroupConfigsUpdated(AccountId(groupId), fromMerge = true))
        }

        job(statusChannel, groupKeys = keys, configUpdates = configUpdates).execute("test")

        assertTrue(statusChannel.receive().isSuccess)
    }

    @Test
    fun `sending to a group we hold keys for is sent`() = runTest {
        val statusChannel = Channel<Result<Unit>>(capacity = 1)

        job(statusChannel, groupKeys = listOf(ByteArray(32))).execute("test")

        assertTrue(statusChannel.receive().isSuccess)
    }

    private fun job(
        statusChannel: Channel<Result<Unit>>,
        groupKeys: List<ByteArray>,
    ): MessageSendJob = job(statusChannel, MutableStateFlow(groupKeys))

    private fun job(
        statusChannel: Channel<Result<Unit>>,
        groupKeys: StateFlow<List<ByteArray>>,
        // The real notification flow never completes; an ending flow would fail the wait
        // outright instead of exercising the timeout
        configUpdates: Flow<ConfigUpdateNotification> = MutableSharedFlow(),
    ): MessageSendJob {
        val keysConfig = mockk<ReadableGroupKeysConfig> {
            every { keys() } answers { groupKeys.value }
        }

        val configFactory = mockk<ConfigFactoryProtocol> {
            every { configUpdateNotifications } returns configUpdates
            every { dangerouslyAccessGroupConfigs(any()) } returns Pair(
                mockk<GroupConfigs> { every { this@mockk.groupKeys } returns keysConfig },
                {},
            )
        }

        return MessageSendJob(
            message = GroupUpdated(
                SessionProtos.GroupUpdateMessage.newBuilder()
                    .setMemberLeftMessage(SessionProtos.GroupUpdateMemberLeftMessage.getDefaultInstance())
                    .build()
            ),
            destination = Destination.ClosedGroup(groupId),
            statusCallback = statusChannel,
            attachmentUploadJobFactory = mockk(relaxed = true),
            messageDataProvider = mockk(relaxed = true),
            storage = mockk(relaxed = true),
            configFactory = configFactory,
            messageSender = mockk(relaxed = true),
            jobQueue = mockk(relaxed = true),
        )
    }
}

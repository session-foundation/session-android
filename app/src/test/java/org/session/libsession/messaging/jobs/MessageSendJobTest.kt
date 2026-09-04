package org.session.libsession.messaging.jobs

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.thoughtcrime.securesms.NoOpLogger

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
    fun `sending to a group we hold keys for is sent`() = runTest {
        val statusChannel = Channel<Result<Unit>>(capacity = 1)

        job(statusChannel, groupKeys = listOf(ByteArray(32))).execute("test")

        assertTrue(statusChannel.receive().isSuccess)
    }

    private fun job(
        statusChannel: Channel<Result<Unit>>,
        groupKeys: List<ByteArray>,
    ): MessageSendJob {
        val keysConfig = mockk<ReadableGroupKeysConfig> {
            every { keys() } returns groupKeys
        }

        val configFactory = mockk<ConfigFactoryProtocol> {
            // The real notification flow never completes; an ending flow would fail the
            // wait outright instead of exercising the timeout
            every { configUpdateNotifications } returns MutableSharedFlow<ConfigUpdateNotification>()
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

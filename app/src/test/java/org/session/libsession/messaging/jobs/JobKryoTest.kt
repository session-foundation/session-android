package org.session.libsession.messaging.jobs

import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.utilities.Data
import org.session.protos.SessionProtos

class JobKryoTest {
    @Test
    fun `GroupUpdated survives a round trip`() {
        val message = GroupUpdated(
            SessionProtos.GroupUpdateMessage.newBuilder()
                .setMemberLeftMessage(SessionProtos.GroupUpdateMemberLeftMessage.getDefaultInstance())
                .build()
        ).apply {
            sentTimestamp = 1_234_567_890L
        }

        val restored = roundTrip(message)

        assertTrue(restored is GroupUpdated)
        assertEquals(message.inner, (restored as GroupUpdated).inner)
        assertEquals(message.sentTimestamp, restored.sentTimestamp)
    }

    /**
     * The round trip above proves the serializer; this proves the job is wired to it, in both
     * directions — a `serialize()` and a `create()` that disagree lose the message silently.
     */
    @Test
    fun `a GroupUpdated send job survives serialize and deserialize`() {
        val message = GroupUpdated(
            SessionProtos.GroupUpdateMessage.newBuilder()
                .setMemberLeftMessage(SessionProtos.GroupUpdateMemberLeftMessage.getDefaultInstance())
                .build()
        )

        val restored = slot<Message>()
        val factory = mockk<MessageSendJob.Factory> {
            every { create(any<Data>()) } answers { callOriginal() }
            every { create(capture(restored), any(), any()) } returns mockk(relaxed = true)
        }

        assertNotNull(factory.create(sendJob(message).serialize()))
        assertEquals(message.inner, (restored.captured as GroupUpdated).inner)
    }

    private fun sendJob(message: Message) = MessageSendJob(
        message = message,
        destination = Destination.ClosedGroup("03${"11".repeat(32)}"),
        statusCallback = null,
        attachmentUploadJobFactory = mockk(relaxed = true),
        messageDataProvider = mockk(relaxed = true),
        storage = mockk(relaxed = true),
        configFactory = mockk(relaxed = true),
        messageSender = mockk(relaxed = true),
        jobQueue = mockk(relaxed = true),
    )

    private fun roundTrip(value: Any): Any {
        val output = Output(ByteArray(4096), Job.MAX_BUFFER_SIZE_BYTES)
        jobKryo().writeClassAndObject(output, value)
        output.close()

        return jobKryo().readClassAndObject(Input(output.toBytes()))
    }
}

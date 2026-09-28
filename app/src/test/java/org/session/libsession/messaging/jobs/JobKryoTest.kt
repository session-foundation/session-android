package org.session.libsession.messaging.jobs

import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.session.libsession.messaging.messages.control.GroupUpdated
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

    private fun roundTrip(value: Any): Any {
        val output = Output(ByteArray(4096), Job.MAX_BUFFER_SIZE_BYTES)
        jobKryo().writeClassAndObject(output, value)
        output.close()

        return jobKryo().readClassAndObject(Input(output.toBytes()))
    }
}

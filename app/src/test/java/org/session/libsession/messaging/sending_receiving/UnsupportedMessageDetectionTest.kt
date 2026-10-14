package org.session.libsession.messaging.sending_receiving

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.session.libsession.messaging.messages.UnsupportedMessage.Placement
import org.session.protos.SessionProtos

/**
 * Synthetic payloads only: every byte here is built by the test.
 */
class UnsupportedMessageDetectionTest {

    private val knownText: ByteArray = SessionProtos.Content.newBuilder()
        .setDataMessage(SessionProtos.DataMessage.newBuilder().setBody("synthetic"))
        .build()
        .toByteArray()

    // Field 18 (msgId in newer clients), varint 1
    private val field18 = byteArrayOf(0x90.toByte(), 0x01, 0x01)

    // Field 19, length-delimited, two bytes
    private val field19 = byteArrayOf(0x9A.toByte(), 0x01, 0x02, 0xAA.toByte(), 0xBB.toByte())

    @Test
    fun `scan returns the top-level field numbers in order`() {
        val data = knownText + field18 + field19

        // DataMessage is field 1 of Content
        assertEquals(listOf(1, 18, 19), UnsupportedMessageDetection.topLevelFieldNumbers(data))
    }

    @Test
    fun `scan handles every supported wire type`() {
        val data = byteArrayOf(
            0x08, 0x96.toByte(), 0x01,                    // 1: varint 150
            0x11, 1, 2, 3, 4, 5, 6, 7, 8,                 // 2: fixed64
            0x1A, 0x01, 0x00,                             // 3: length-delimited, 1 byte
            0x25, 1, 2, 3, 4,                             // 4: fixed32
        )

        assertEquals(listOf(1, 2, 3, 4), UnsupportedMessageDetection.topLevelFieldNumbers(data))
    }

    @Test
    fun `scan of empty data is an empty list`() {
        assertEquals(emptyList<Int>(), UnsupportedMessageDetection.topLevelFieldNumbers(ByteArray(0)))
    }

    @Test
    fun `scan rejects malformed data`() {
        // Truncated length-delimited field
        assertNull(UnsupportedMessageDetection.topLevelFieldNumbers(byteArrayOf(0x9A.toByte(), 0x01, 0x05, 0x00)))
        // Truncated fixed64 / fixed32
        assertNull(UnsupportedMessageDetection.topLevelFieldNumbers(byteArrayOf(0x11, 1, 2, 3)))
        assertNull(UnsupportedMessageDetection.topLevelFieldNumbers(byteArrayOf(0x25, 1, 2)))
        // Unterminated varint
        assertNull(UnsupportedMessageDetection.topLevelFieldNumbers(byteArrayOf(0x08, 0x80.toByte())))
        // Field number 0
        assertNull(UnsupportedMessageDetection.topLevelFieldNumbers(byteArrayOf(0x00, 0x01)))
        // Group wire types (3, 4) and the undefined 6, 7
        for (wireType in listOf(3, 4, 6, 7)) {
            assertNull(UnsupportedMessageDetection.topLevelFieldNumbers(byteArrayOf(((1 shl 3) or wireType).toByte(), 0x00)))
        }
    }

    @Test
    fun `fields up to 18 are known`() {
        assertFalse(UnsupportedMessageDetection.containsUnknownContentType(knownText))
        assertFalse(UnsupportedMessageDetection.containsUnknownContentType(knownText + field18))
        assertTrue(UnsupportedMessageDetection.containsUnknownContentType(knownText + field19))
    }

    @Test
    fun `malformed content is never an unknown type`() {
        assertFalse(UnsupportedMessageDetection.containsUnknownContentType(field19.copyOf(3)))
    }

    @Test
    fun `an unknown field only counts without valid known content`() {
        val unknown = knownText + field19

        assertTrue(UnsupportedMessageDetection.isUnknownType(unknown) { false })
        assertFalse(UnsupportedMessageDetection.isUnknownType(unknown) { true })
        assertFalse(UnsupportedMessageDetection.isUnknownType(knownText + field18) { false })
        assertFalse(UnsupportedMessageDetection.isUnknownType(ByteArray(0)) { false })
    }

    @Test
    fun `content with an unknown field still parses with the generated classes`() {
        val content = SessionProtos.Content.parseFrom(knownText + field19)

        assertEquals("synthetic", content.dataMessage.body)
    }

    @Test
    fun `only data starting with a zero byte is a newer format`() {
        assertTrue(UnsupportedMessageDetection.isNewerFormat(byteArrayOf(0x00, 0x02, 0x7F)))
        assertTrue(UnsupportedMessageDetection.isNewerFormat(byteArrayOf(0x00, 0x05)))
        assertFalse(UnsupportedMessageDetection.isNewerFormat(knownText))
        assertFalse(UnsupportedMessageDetection.isNewerFormat(ByteArray(0)))
    }

    @Test
    fun `placement follows the sender and sync target`() {
        val target = "05" + "ab".repeat(32)

        assertEquals(Placement.INCOMING to null, UnsupportedMessageDetection.placement(isGroup = false, isSenderSelf = false, syncTarget = target))
        assertEquals(Placement.OUTGOING to target, UnsupportedMessageDetection.placement(isGroup = false, isSenderSelf = true, syncTarget = target))
        assertEquals(Placement.NONE to null, UnsupportedMessageDetection.placement(isGroup = false, isSenderSelf = true, syncTarget = null))
        // Only a standard (05) account can be a sync target
        assertEquals(Placement.NONE to null, UnsupportedMessageDetection.placement(isGroup = false, isSenderSelf = true, syncTarget = "03" + "ab".repeat(32)))
        assertEquals(Placement.NONE to null, UnsupportedMessageDetection.placement(isGroup = false, isSenderSelf = true, syncTarget = "not an id"))
        assertEquals(Placement.INCOMING to null, UnsupportedMessageDetection.placement(isGroup = true, isSenderSelf = false, syncTarget = null))
        assertEquals(Placement.OUTGOING to null, UnsupportedMessageDetection.placement(isGroup = true, isSenderSelf = true, syncTarget = target))
    }

    @Test
    fun `retained expiry prefers an after-send timer`() {
        assertEquals(
            1_000L + 60_000L,
            UnsupportedMessageDetection.retainedExpiryMs(
                afterSendStartedAtMs = 1_000L,
                afterSendDurationMs = 60_000L,
                serverTimestampMs = 900L,
                serverExpiryMs = 900L + DEFAULT_TTL,
                defaultTtlMs = DEFAULT_TTL,
            )
        )
    }

    @Test
    fun `retained expiry uses the swarm expiry only when shorter than the default TTL`() {
        assertEquals(
            5_000L,
            UnsupportedMessageDetection.retainedExpiryMs(null, null, serverTimestampMs = 1_000L, serverExpiryMs = 5_000L, defaultTtlMs = DEFAULT_TTL)
        )
        assertNull(
            UnsupportedMessageDetection.retainedExpiryMs(null, null, serverTimestampMs = 1_000L, serverExpiryMs = 1_000L + DEFAULT_TTL, defaultTtlMs = DEFAULT_TTL)
        )
        assertNull(
            UnsupportedMessageDetection.retainedExpiryMs(null, null, serverTimestampMs = 1_000L, serverExpiryMs = null, defaultTtlMs = DEFAULT_TTL)
        )
    }

    private companion object {
        const val DEFAULT_TTL = 14L * 24 * 60 * 60 * 1000
    }
}

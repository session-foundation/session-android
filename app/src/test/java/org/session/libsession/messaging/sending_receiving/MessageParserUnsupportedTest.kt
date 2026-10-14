package org.session.libsession.messaging.sending_receiving

import io.mockk.every
import io.mockk.mockk
import network.loki.messenger.libsession_util.util.Contact
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.session.libsession.messaging.messages.UnsupportedMessage
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.protos.SessionProtos
import org.thoughtcrime.securesms.NoOpLogger
import org.session.libsignal.utilities.Log

/**
 * The parts of [MessageParser]'s unknown-type detection that run after decryption. Decryption
 * itself is libsession native code, which can't load in a JVM test, so these start from a
 * decrypted `Content`. Synthetic payloads only.
 */
class MessageParserUnsupportedTest {
    private val currentUser = AccountId("05" + "11".repeat(32))
    private val otherUser = AccountId("05" + "22".repeat(32))
    private val syncTarget = "05" + "33".repeat(32)
    private val messageTimestampMs = 1_700_000_000_000L

    // Field 19, length-delimited, one byte
    private val unknownField = byteArrayOf(0x9A.toByte(), 0x01, 0x01, 0x00)

    private val blockedContacts = mutableSetOf<String>()

    private lateinit var parser: MessageParser

    @Before
    fun setUp() {
        Log.initialize(NoOpLogger)

        val userConfigs = mockk<UserConfigs> {
            every { contacts.get(any()) } answers {
                val id = firstArg<String>()
                if (id in blockedContacts) mockk<Contact> { every { blocked } returns true } else null
            }
        }

        parser = MessageParser(
            configFactory = mockk<ConfigFactoryProtocol> {
                every { dangerouslyAccessUserConfigs() } returns (userConfigs to {})
            },
            storage = mockk(relaxed = true),
            snodeClock = mockk<SnodeClock> { every { currentTimeMillis() } returns messageTimestampMs + 1 },
            proBackendConfig = { mockk() },
            proDatabase = mockk(relaxed = true),
        )
    }

    private fun content(build: SessionProtos.Content.Builder.() -> Unit): SessionProtos.Content =
        SessionProtos.Content.newBuilder().apply(build).build()

    private fun hasValidKnownContent(content: SessionProtos.Content, isForGroup: Boolean = false) =
        parser.hasValidKnownContent(
            proto = content,
            isForGroup = isForGroup,
            sender = otherUser,
            currentUserId = currentUser,
            messageTimestampMs = messageTimestampMs,
        )

    private fun isUnknownType(content: SessionProtos.Content): Boolean {
        val plaintext = content.toByteArray() + unknownField
        return UnsupportedMessageDetection.isUnknownType(plaintext) {
            hasValidKnownContent(SessionProtos.Content.parseFrom(plaintext))
        }
    }

    private fun parseUnknown(
        content: SessionProtos.Content,
        sender: AccountId,
        isForGroup: Boolean = false,
    ): UnsupportedMessage = parser.parseUnknownTypeMessage(
        proto = content,
        sender = sender,
        messageTimestampMs = messageTimestampMs,
        checkForBlockStatus = true,
        isForGroup = isForGroup,
        currentUserId = currentUser,
        swarmOrigin = MessageParser.SwarmOrigin(
            rawData = byteArrayOf(1, 2, 3),
            swarmPublicKey = currentUser.hexString,
            namespace = 0,
            serverHash = "synthetic-hash",
            serverTimestampMs = messageTimestampMs,
            serverExpiryMs = null,
        ),
    ).message as UnsupportedMessage

    @Test
    fun `known content with an unknown field is processed normally`() {
        assertFalse(isUnknownType(content {
            setDataMessage(SessionProtos.DataMessage.newBuilder().setBody("synthetic"))
        }))
    }

    @Test
    fun `an unknown field with no known content is an unknown type`() {
        assertTrue(isUnknownType(content { setSigTimestamp(messageTimestampMs) }))
    }

    @Test
    fun `a sync copy carrying only a sync target is an unknown type`() {
        assertTrue(isUnknownType(content {
            setDataMessage(SessionProtos.DataMessage.newBuilder().setSyncTarget(syncTarget))
        }))
    }

    @Test
    fun `invalid known content without an unknown field is not an unknown type`() {
        val plaintext = content { setDataMessage(SessionProtos.DataMessage.newBuilder()) }.toByteArray()

        assertFalse(UnsupportedMessageDetection.isUnknownType(plaintext) {
            hasValidKnownContent(SessionProtos.Content.parseFrom(plaintext))
        })
    }

    @Test
    fun `an unknown type from someone else is incoming and keeps its expiry`() {
        val message = parseUnknown(
            content {
                expirationType = SessionProtos.Content.ExpirationType.DELETE_AFTER_SEND
                expirationTimer = 60
            },
            sender = otherUser,
        )

        assertEquals(UnsupportedMessage.Kind.UNKNOWN_TYPE, message.kind)
        assertEquals(UnsupportedMessage.Placement.INCOMING, message.placement)
        assertEquals(otherUser.hexString, message.sender)
        assertEquals(messageTimestampMs, message.sentTimestamp)
        assertEquals("synthetic-hash", message.serverHash)
        assertEquals(ExpiryMode.AfterSend(60), message.expiryMode)
    }

    @Test
    fun `an unknown type from a blocked sender is dropped`() {
        blockedContacts += otherUser.hexString

        assertThrows(NonRetryableException::class.java) {
            parseUnknown(content { }, sender = otherUser)
        }
    }

    @Test
    fun `a sync from our own device is placed in its sync target`() {
        val message = parseUnknown(
            content { setDataMessage(SessionProtos.DataMessage.newBuilder().setSyncTarget(syncTarget)) },
            sender = currentUser,
        )

        assertEquals(UnsupportedMessage.Placement.OUTGOING, message.placement)
        assertEquals(syncTarget, message.syncTarget)
    }

    @Test
    fun `a sync from our own device without a sync target has no placement`() {
        val message = parseUnknown(content { }, sender = currentUser)

        assertEquals(UnsupportedMessage.Placement.NONE, message.placement)
        assertNull(message.syncTarget)
    }

    @Test
    fun `a group message from our own device is outgoing in the group`() {
        val message = parseUnknown(content { }, sender = currentUser, isForGroup = true)

        assertEquals(UnsupportedMessage.Placement.OUTGOING, message.placement)
    }
}

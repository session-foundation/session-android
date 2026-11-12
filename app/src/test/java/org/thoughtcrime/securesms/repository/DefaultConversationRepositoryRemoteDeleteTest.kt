package org.thoughtcrime.securesms.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.util.KeyPair
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.snode.DeleteMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.util.RecordingLogger

class DefaultConversationRepositoryRemoteDeleteTest {

    private val userPublicKey = "05" + "ef".repeat(32)
    private val userAddress: Address = userPublicKey.toAddress()
    private val recipient: Address = ("05" + "12".repeat(32)).toAddress()

    private val message: MessageRecord = mock {
        on { messageId } doReturn MessageId(id = 1L, mms = false)
        on { isOutgoing } doReturn true
        on { timestamp } doReturn 1_700_000_000_000L
    }

    private val messageSender: MessageSender = mock()

    private val logger = RecordingLogger()

    @Before
    fun setUp() {
        Log.initialize(logger)
    }

    private fun createRepository(swarmApiExecutor: SwarmApiExecutor) = DefaultConversationRepository(
        messageDataProvider = mock<MessageDataProvider> {
            on { getServerHashForMessage(any()) } doReturn "serverHash"
        },
        threadDb = mock(),
        communityDatabase = mock(),
        draftDb = mock(),
        smsDb = mock(),
        mmsDb = mock(),
        mmsSmsDb = mock(),
        // userAuth is an extension property, so it is satisfied through what it reads.
        storage = mock<Storage> {
            on { getUserPublicKey() } doReturn userPublicKey
            on { getUserED25519KeyPair() } doReturn KeyPair(ByteArray(32), ByteArray(64))
        },
        lokiMessageDb = mock(),
        configFactory = mock(),
        groupManager = mock(),
        clock = mock(),
        recipientDatabase = mock(),
        recipientRepository = mock(),
        messageSender = messageSender,
        loginStateRepository = mock<LoginStateRepository> {
            on { requireLocalNumber() } doReturn userPublicKey
        },
        proStatusManager = mock(),
        swarmApiExecutor = swarmApiExecutor,
        communityApiExecutor = mock(),
        deleteSwarmMessageApiFactory = mock<DeleteMessageApi.Factory> {
            on { create(any(), any()) } doReturn mock()
        },
        deleteCommunityMessageApiFactory = mock(),
        banUserApiFactory = mock(),
        unbanUserApiFactory = mock(),
        deleteUserMessageApiFactory = mock(),
        json = mock(),
    )

    private fun failingSwarm(error: Exception): SwarmApiExecutor = mock {
        onBlocking { send(any(), any()) } doThrow error
    }

    private fun assertLogged(error: Exception) {
        assertTrue("expected $error to be logged", logger.entries.any { it.throwable === error })
    }

    private fun verifyUnsendSentTo(address: Address) {
        verify(messageSender).send(argThat { this is UnsendRequest }, eq(address), anyOrNull())
    }

    @Test
    fun `1on1 delete still sends both UnsendRequests when the own-swarm delete fails`() = runTest {
        val error = IllegalStateException("No snodes reported successful deletion of messages")
        val swarm = failingSwarm(error)

        createRepository(swarm).delete1on1MessagesRemotely(recipient, setOf(message))

        // Proves the failing delete was reached, not skipped.
        verify(swarm, times(1)).send(any(), any())
        verifyUnsendSentTo(userAddress)
        verifyUnsendSentTo(recipient)
        assertLogged(error)
    }

    @Test
    fun `note to self delete still sends its UnsendRequest when the own-swarm delete fails`() = runTest {
        val error = IllegalStateException("No snodes reported successful deletion of messages")
        val swarm = failingSwarm(error)

        createRepository(swarm).deleteNoteToSelfMessagesRemotely(userAddress, setOf(message))

        verify(swarm, times(1)).send(any(), any())
        verifyUnsendSentTo(userAddress)
        assertLogged(error)
    }

    @Test
    fun `cancellation of the own-swarm delete is not swallowed`() {
        val swarm = failingSwarm(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                createRepository(swarm).delete1on1MessagesRemotely(recipient, setOf(message))
            }
        }

        verify(messageSender, never()).send(any(), any(), anyOrNull())
    }
}

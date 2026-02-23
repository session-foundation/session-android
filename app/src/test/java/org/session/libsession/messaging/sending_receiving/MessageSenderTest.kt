package org.session.libsession.messaging.sending_receiving

import kotlinx.coroutines.CoroutineScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.times
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.api.CommunityApiExecutor
import org.session.libsession.messaging.open_groups.api.SendDirectMessageApi
import org.session.libsession.messaging.open_groups.api.SendMessageApi
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.thoughtcrime.securesms.api.snode.StoreMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.service.ExpiringMessageManager

@RunWith(MockitoJUnitRunner::class)
class MessageSenderTest {

    @Mock
    private lateinit var storage: StorageProtocol

    @Mock
    private lateinit var configFactory: ConfigFactoryProtocol

    @Mock
    private lateinit var recipientRepository: RecipientRepository

    @Mock
    private lateinit var messageDataProvider: MessageDataProvider

    @Mock
    private lateinit var messageSendJobFactory: MessageSendJob.Factory

    @Mock
    private lateinit var messageExpirationManager: ExpiringMessageManager

    @Mock
    private lateinit var snodeClock: SnodeClock

    @Mock
    private lateinit var communityApiExecutor: CommunityApiExecutor

    @Mock
    private lateinit var sendCommunityMessageApiFactory: SendMessageApi.Factory

    @Mock
    private lateinit var sendCommunityDirectMessageApiFactory: SendDirectMessageApi.Factory

    @Mock
    private lateinit var swarmApiExecutor: SwarmApiExecutor

    @Mock
    private lateinit var storeSnodeMessageApiFactory: StoreMessageApi.Factory

    @Mock
    private lateinit var scope: CoroutineScope

    @Mock
    private lateinit var loginStateRepository: LoginStateRepository

    @Mock
    private lateinit var jobQueue: JobQueue

    @Mock
    private lateinit var messageSendJob: MessageSendJob

    private lateinit var messageSender: MessageSender

    @Before
    fun setUp() {
        messageSender = MessageSender(
            storage,
            configFactory,
            recipientRepository,
            messageDataProvider,
            messageSendJobFactory,
            messageExpirationManager,
            snodeClock,
            communityApiExecutor,
            sendCommunityMessageApiFactory,
            sendCommunityDirectMessageApiFactory,
            swarmApiExecutor,
            storeSnodeMessageApiFactory,
            scope,
            loginStateRepository
        )
        jobQueue = JobQueue.shared
    }

    @Test
    fun `given a message and address, when sending, then a message send job is created and added to the queue`() {
        val message = VisibleMessage(text = "Hello")
        val address =
            Address.fromSerialized("0529eca7b7ca0fefb87ee1362ac1a648da68953e51ab71bedc2c8b42b744d5c35f")

        `when`(messageSendJobFactory.create(any(), any(), anyOrNull())).thenReturn(messageSendJob)

        messageSender.send(message, address)

        verify(messageSendJobFactory).create(message, Destination.from(address), null)
        verify(jobQueue).add(messageSendJob)
    }

    @Test
    fun `given 101 messages and an address, when sending, then 101 message send jobs are created and added to the queue`() {
        val messages = (1..101).map { VisibleMessage(text = "Hello $it") }
        val address =
            Address.fromSerialized("0529eca7b7ca0fefb87ee1362ac1a648da68953e51ab71bedc2c8b42b744d5c35f")

        `when`(messageSendJobFactory.create(any(), any(), anyOrNull())).thenReturn(messageSendJob)

        messages.forEach { messageSender.send(it, address) }

        verify(messageSendJobFactory, times(101)).create(any(), any(), anyOrNull())
        verify(jobQueue, times(101)).add(messageSendJob)
    }

    @Test
    fun `given more than 100 messages and an address, when sending, then log the results`() {
        val messageCount = 110
        val messages = (1..messageCount).map { VisibleMessage(text = "Hello $it") }
        val address =
            Address.fromSerialized("0529eca7b7ca0fefb87ee1362ac1a648da68953e51ab71bedc2c8b42b744d5c35f")

        `when`(messageSendJobFactory.create(any(), any(), anyOrNull())).thenReturn(messageSendJob)

        println("Sending $messageCount messages...")
        messages.forEach { message ->
            messageSender.send(message, address)
            println("Queued message for sending: '${message.text}' to address '$address'")
        }

        verify(messageSendJobFactory, times(messageCount)).create(any(), any(), anyOrNull())
        verify(jobQueue, times(messageCount)).add(messageSendJob)
        println("All $messageCount messages have been successfully queued.")
    }
}

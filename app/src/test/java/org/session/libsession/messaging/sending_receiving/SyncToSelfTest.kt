package org.session.libsession.messaging.sending_receiving

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.jobs.Job
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.NoOpLogger
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.model.MessageId
import java.io.IOException
import javax.inject.Provider

/**
 * The sync leg of a 1:1 send: after a message reaches its recipient, a copy goes to our own swarm.
 *
 * Encoding a message for the network needs libsession's native library, which JVM tests can't load,
 * so the network fake sits at [MessageSender.sendNonDurably]. It writes the statuses
 * [MessageSender] itself writes for a sync send: SYNC_FAILED when the send throws, SENT when it lands.
 * Everything between that and [MessageSender.scheduleSyncToSelf] is the real job queue.
 */
class SyncToSelfTest {
    private val self = "05" + "aa".repeat(32)
    private val contact = "05" + "bb".repeat(32)
    private val messageId = MessageId(42, false)

    /** Every status written to the message, in order. */
    private val statuses = mutableListOf<String>()

    private val storage = mockk<Storage>(relaxed = true) {
        every { getUserPublicKey() } returns self
        every { isJobCanceled(any()) } returns false
        every { getAllPendingJobs(*anyVararg()) } returns emptyMap()
        every { markAsSyncing(any()) } answers { statuses += SYNCING }
        every { markAsSending(any()) } answers { statuses += SENDING }
        every { markAsSyncFailed(any(), any()) } answers { statuses += SYNC_FAILED }
        every { markAsSent(any()) } answers { statuses += SENT }
    }

    private val messageDataProvider = mockk<MessageDataProvider>(relaxed = true) {
        every { isDeletedMessage(any()) } returns false
        every { isOutgoingMessage(any()) } returns true
    }

    /** Each send that reached the fake network: its destination and whether it was a sync. */
    private val sends = mutableListOf<Pair<Destination, Boolean>>()

    @Before
    fun setUp() {
        Log.initialize(NoOpLogger)
    }

    /** The fake network every sync job sends through. */
    private var network: MessageSender = mockk(relaxed = true)

    @Test
    fun `a sync that fails transiently is retried until the message is sent`() = runTest {
        network = network(failuresBeforeSuccess = 3)
        val queue = JobQueue(queueScope(), storage)

        messageSender(queue).scheduleSyncToSelf(storedMessage(), self)
        advanceUntilIdle()

        assertEquals(List(4) { Destination.Contact(self) to true }, sends)
        assertEquals(
            listOf(
                SYNCING,
                SYNCING, SYNC_FAILED,
                SYNCING, SYNC_FAILED,
                SYNCING, SYNC_FAILED,
                SYNCING, SENT,
            ),
            statuses
        )
        verify(exactly = 1) { storage.markJobAsSucceeded(any()) }
    }

    @Test
    fun `a sync that never succeeds gives up after the normal send retry limit`() = runTest {
        network = network(failuresBeforeSuccess = Int.MAX_VALUE)
        val queue = JobQueue(queueScope(), storage)

        messageSender(queue).scheduleSyncToSelf(storedMessage(), self)
        advanceUntilIdle()

        assertEquals(10, sends.size)
        assertEquals(SYNC_FAILED, statuses.last())
        verify(exactly = 1) { storage.markJobAsFailedPermanently(any()) }
    }

    @Test
    fun `a sync left pending across a restart is resumed and sent`() = runTest {
        network = network(failuresBeforeSuccess = 0)

        // Before the restart: the sync is scheduled and persisted, and the process dies before it runs
        val scheduled = slot<Job>()
        val deadQueue = mockk<JobQueue>(relaxed = true) {
            every { add(capture(scheduled)) } returns Unit
        }
        messageSender(deadQueue).scheduleSyncToSelf(storedMessage(), self)
        val persisted = scheduled.captured.serialize()
        statuses.clear()

        // After the restart: a fresh queue reads the job back from storage
        val restored = jobFactory.create(persisted)!!.apply { id = "1" }
        every { storage.getAllPendingJobs(MessageSendJob.KEY) } returns mapOf("1" to restored)

        JobQueue(queueScope(), storage).resumePendingJobs()
        advanceUntilIdle()

        assertEquals(listOf(Destination.Contact(self) to true), sends)
        assertEquals(listOf(SYNCING, SENT), statuses)
    }

    @Test
    fun `only a message with a stored record gets a durable sync`() {
        val queue = mockk<JobQueue>(relaxed = true)
        val sender = messageSender(queue)

        sender.scheduleSyncToSelf(VisibleMessage(syncTarget = contact, text = "hi"), self)
        verify(exactly = 0) { queue.add(any()) }

        sender.scheduleSyncToSelf(storedMessage(), self)
        verify(exactly = 1) { queue.add(any()) }
    }

    /** Note to Self is also a send to our own swarm, but a first send, so it must still read as sending. */
    @Test
    fun `a Note to Self send is reported as sending, a sync as syncing`() = runTest {
        network = network(failuresBeforeSuccess = 0)

        jobFactory.create(storedMessage(), Destination.Contact(self), isSyncOfSentMessage = false).execute("test")
        jobFactory.create(storedMessage(), Destination.Contact(self), isSyncOfSentMessage = true).execute("test")

        assertEquals(listOf(SENDING, SENT, SYNCING, SENT), statuses)
    }

    private fun storedMessage() = VisibleMessage(syncTarget = contact, text = "hi").apply {
        id = messageId
        sentTimestamp = 1_700_000_000_000L
        threadID = 7L
    }

    // advanceUntilIdle() leaves backgroundScope work unrun, and the queue's dispatch loop never ends,
    // so it gets a foreground scope on the test's scheduler that is cancelled when the test finishes
    private fun TestScope.queueScope(): CoroutineScope {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        queueScopes += scope
        return scope
    }

    private val queueScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        queueScopes.forEach { it.cancel() }
    }

    /** Fails the first [failuresBeforeSuccess] sends, then succeeds. */
    private fun network(failuresBeforeSuccess: Int): MessageSender = mockk {
        coEvery { sendNonDurably(any<Message>(), any<Destination>(), any<Boolean>()) } coAnswers {
            val message = firstArg<Message>()
            sends += secondArg<Destination>() to thirdArg<Boolean>()
            if (sends.size <= failuresBeforeSuccess) {
                val error = IOException("snode unreachable")
                storage.markAsSyncFailed(message.id!!, error)
                throw error
            }
            storage.markAsSent(message.id!!)
        }
    }

    private val jobFactory = object : MessageSendJob.Factory() {
        override fun create(
            message: Message,
            destination: Destination,
            statusCallback: SendChannel<Result<Unit>>?,
            isSyncOfSentMessage: Boolean,
        ) = MessageSendJob(
            message = message,
            destination = destination,
            statusCallback = statusCallback,
            isSyncOfSentMessage = isSyncOfSentMessage,
            attachmentUploadJobFactory = mockk(relaxed = true),
            messageDataProvider = messageDataProvider,
            storage = storage,
            configFactory = mockk(relaxed = true),
            messageSender = network,
            jobQueue = mockk(relaxed = true),
        )
    }

    // The scope only runs the best-effort sync for a message with no stored record. It is never
    // advanced, so that sync never reaches the (native) encoder.
    private fun messageSender(queue: JobQueue) = MessageSender(
        storage = storage,
        configFactory = mockk(relaxed = true),
        recipientRepository = mockk(relaxed = true),
        messageDataProvider = messageDataProvider,
        messageSendJobFactory = jobFactory,
        messageExpirationManager = mockk(relaxed = true),
        snodeClock = mockk(relaxed = true),
        communityApiExecutor = mockk(relaxed = true),
        sendCommunityMessageApiFactory = mockk(relaxed = true),
        sendCommunityDirectMessageApiFactory = mockk(relaxed = true),
        swarmApiExecutor = mockk(relaxed = true),
        storeSnodeMessageApiFactory = mockk(relaxed = true),
        scope = CoroutineScope(StandardTestDispatcher()),
        loginStateRepository = mockk(relaxed = true),
        jobQueue = Provider { queue },
        proStatusManager = Provider { mockk(relaxed = true) },
    )

    private companion object {
        const val SENDING = "SENDING"
        const val SYNCING = "SYNCING"
        const val SYNC_FAILED = "SYNC_FAILED"
        const val SENT = "SENT"
    }
}

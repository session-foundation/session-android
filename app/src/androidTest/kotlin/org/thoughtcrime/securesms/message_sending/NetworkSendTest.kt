package org.thoughtcrime.securesms.message_sending

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.DebugTextSendJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.crypto.MnemonicCodec
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.crypto.MnemonicUtilities
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.model.MessageId
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NetworkSendTest {

    companion object {
        //  Message Counts
        private const val NTS_MESSAGE_COUNT = 100
        private const val ONE_ON_ONE_MESSAGE_COUNT = 100
        private const val GROUP_MESSAGE_COUNT = 20
        private const val COMMUNITY_MESSAGE_COUNT = 5

        // Delays
        private const val DEFAULT_DELAY_MS = 250L

        // Timeouts
        private const val DEFAULT_EXECUTION_TIMEOUT_MS = 120_000L
        private const val LONG_EXECUTION_TIMEOUT_MS = 300_000L
        private const val DEFAULT_AWAIT_TIMEOUT_MS = 180_000L
        private const val LONG_AWAIT_TIMEOUT_MS = 300_000L

        // Polling
        private const val POLL_INTERVAL_MS = 200L
    }

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var loginStateRepository: LoginStateRepository

    @Inject lateinit var messageSenderProvider: Provider<MessageSender>
    @Inject lateinit var snodeClockProvider: Provider<SnodeClock>
    @Inject lateinit var storageProvider: Provider<Storage>
    @Inject lateinit var jobQueueProvider: Provider<JobQueue>
    @Inject lateinit var mmsSmsDatabaseProvider: Provider<MmsSmsDatabase>

    @Inject lateinit var messagingModuleConfiguration: Provider<MessagingModuleConfiguration>

    @Inject lateinit var recipientRepository: RecipientRepository
    @Inject lateinit var debugTextSendJobFactory: DebugTextSendJob.Factory

    // Resolved after we seed login state
    private lateinit var messageSender: MessageSender
    private lateinit var snodeClock: SnodeClock
    private lateinit var storage: Storage
    private lateinit var jobQueue: JobQueue
    private lateinit var mmsSmsDb: MmsSmsDatabase

    @Before fun setup() {
        // SQLCipher relies on native libraries, but the test application does not run the normal app
        // initialization. We prepare anything the database needs here before injection happens.
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        MessagingModuleConfiguration.configure(context)

        // Skip the automatic VACUUM step during tests to keep startup faster and more stable.
        runCatching {
            TextSecurePreferences.setLastVacuumNow(context)
        }

        // Explicitly load the SQLCipher native library before anything opens the database.
        runCatching { System.loadLibrary("sqlcipher") }
            .onFailure { throw IllegalStateException("Failed to load SQLCipher native lib (sqlcipher)", it) }

        // Perform injection on the main thread so any Android handlers created during setup
        // are attached to the main looper.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            hiltRule.inject()
        }

        // Generate a login state the same way the app normally would, but inside the test.
        val phrase = "blender balding sabotage javelin cogs fetches duke fatal hitched village sensible oars sensible"

        val codec = MnemonicCodec { fileName ->
            MnemonicUtilities.loadFileContents(context, fileName)
        }

        val seed = codec.sanitizeAndDecodeAsByteArray(phrase)

        // LoggedInState expects a 16‑byte seed.
        check(seed.size == 16) { "Unexpected seed length=${seed.size}, expected 16" }

        loginStateRepository.update { LoggedInState.generate(seed) }

        // After login state is set, we can safely create database and networking singletons.
        // Some of them create Android Handlers, so this must run on the main thread.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            snodeClock = snodeClockProvider.get()
            jobQueue = jobQueueProvider.get()
            messageSender = messageSenderProvider.get()

            // Attempt to reuse the existing encrypted database. If it cannot be opened
            // (for example due to a mismatched key), delete it once and retry.
            storage = try {
                storageProvider.get()
            } catch (t: Throwable) {
                if (looksLikeSqlCipherWrongKeyOrCorruptDb(t)) {
                    Log.w("NetworkSendTest", "Opening session.db failed; deleting and retrying once", t)
                    deleteSessionDb(context)
                    storageProvider.get()
                } else {
                    throw t
                }
            }
            mmsSmsDb = mmsSmsDatabaseProvider.get()
        }
    }

    private fun deleteSessionDb(context: Context) {
        runCatching { context.deleteDatabase("session.db") }
        runCatching { context.getDatabasePath("session.db-wal").delete() }
        runCatching { context.getDatabasePath("session.db-shm").delete() }
    }

    private fun looksLikeSqlCipherWrongKeyOrCorruptDb(t: Throwable): Boolean {
        // SQLCipher often reports key mismatches as "file is not a database".
        // Only retry for known database corruption or wrong‑key cases.
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message?.lowercase().orEmpty()
            if (
                msg.contains("file is not a database") ||
                msg.contains("not a database") ||
                msg.contains("file is encrypted") ||
                msg.contains("malformed")
            ) return true

            // Some devices wrap the real exception; also catch common SQLite exception types by name
            val name = cur.javaClass.name
            if (
                name.contains("SQLiteException") &&
                (msg.contains("not a database") || msg.contains("file is not a database") || msg.contains("malformed"))
            ) return true

            cur = cur.cause
        }
        return false
    }

    private data class BatchSummary(
        val attempted: Int,
        val sent: List<MessageId>,
        val failed: List<MessageId>,
        val timedOut: List<MessageId>,
    )

    private suspend fun awaitTerminalStates(
        ids: List<MessageId>,
        timeoutMs: Long = 180_000L,
        pollMs: Long = 200L,
    ): BatchSummary = withTimeout(timeoutMs) {
        val remaining = ids.toMutableSet()
        val sent = mutableListOf<MessageId>()
        val failed = mutableListOf<MessageId>()

        while (remaining.isNotEmpty()) {
            val it = remaining.iterator()
            while (it.hasNext()) {
                val id = it.next()
                when (mmsSmsDb.getOutgoingTerminalState(id)) {
                    MmsSmsDatabase.OutgoingTerminalState.SENT -> { sent += id; it.remove() }
                    MmsSmsDatabase.OutgoingTerminalState.FAILED -> { failed += id; it.remove() }
                    MmsSmsDatabase.OutgoingTerminalState.PENDING -> Unit
                }
            }
            if (remaining.isNotEmpty()) delay(pollMs)
        }

        BatchSummary(
            attempted = ids.size,
            sent = sent,
            failed = failed,
            timedOut = emptyList()
        )
    }

    @Test fun send_real_network_repeatable_user_nts() = runBlocking {
        val recipient = Address.fromSerialized("05301f684ff55f168fcc270053788609c9a711751c5e636c4e587d804ae435a569")

        // ensure thread exists
        val threadId = storage.getOrCreateThreadIdFor(recipient)

        val job = debugTextSendJobFactory.create(
            threadId = threadId,
            address = recipient,
            count = NTS_MESSAGE_COUNT,
            delayBetweenMessagesMs = DEFAULT_DELAY_MS,
            prefix = "hello from DebugTextSendJob NTS"
        )

        val messageIds = withTimeout(DEFAULT_EXECUTION_TIMEOUT_MS) {
            job.executeAndReturnMessageIds(dispatcherName = "instrumentation-test")
        }

        val summary = awaitTerminalStates(
            ids = messageIds,
            timeoutMs = DEFAULT_AWAIT_TIMEOUT_MS,
            pollMs = POLL_INTERVAL_MS
        )
        println("NetworkSendTest: NTS attempted=${summary.attempted} sent=${summary.sent.size} failed=${summary.failed.size}")
        if (summary.failed.isNotEmpty()) {
            throw AssertionError("NTS failed message ids: ${summary.failed.map { it.id }}")
        }
    }

    @Test fun send_real_network_repeatable_one_on_one() = runBlocking {
        val recipient = Address.fromSerialized("0507012662d6972db5ba1f1f6e5501e3b6c6651c10c593d44153546c69fbe77322")

        // ensure thread exists
        val threadId = storage.getOrCreateThreadIdFor(recipient)

        val job = debugTextSendJobFactory.create(
            threadId = threadId,
            address = recipient,
            count = ONE_ON_ONE_MESSAGE_COUNT,
            delayBetweenMessagesMs = DEFAULT_DELAY_MS,
            prefix = "hello from DebugTextSendJob 1:1"
        )

        val messageIds = withTimeout(LONG_EXECUTION_TIMEOUT_MS) {
            job.executeAndReturnMessageIds(dispatcherName = "instrumentation-test-1:1")
        }

        val summary = awaitTerminalStates(
            ids = messageIds,
            timeoutMs = LONG_AWAIT_TIMEOUT_MS,
            pollMs = POLL_INTERVAL_MS
        )

        Log.i("NetworkSendTest", "1:1 attempted=${summary.attempted} sent=${summary.sent.size} failed=${summary.failed.size}")
        if (summary.failed.isNotEmpty()) {
            throw AssertionError("1:1 failed message ids: ${summary.failed.map { it.id }}")
        }
    }

    @Test fun send_real_network_repeatable_group() = runBlocking {
        val recipient = Address.fromSerialized("034ccd4890302d625eac887b660403140d9a8e131cda797d77d44bec8d5111bc24")

        // ensure thread exists
        val threadId = storage.getOrCreateThreadIdFor(recipient)

        val job = debugTextSendJobFactory.create(
            threadId = threadId,
            address = recipient,
            count = GROUP_MESSAGE_COUNT,
            delayBetweenMessagesMs = DEFAULT_DELAY_MS,
            prefix = "hello from DebugTextSendJob Group"
        )

        val messageIds = withTimeout(DEFAULT_EXECUTION_TIMEOUT_MS) {
            job.executeAndReturnMessageIds(dispatcherName = "instrumentation-test-group")
        }

        val summary = awaitTerminalStates(
            ids = messageIds,
            timeoutMs = DEFAULT_AWAIT_TIMEOUT_MS,
            pollMs = POLL_INTERVAL_MS
        )

        Log.i("NetworkSendTest", "Group attempted=${summary.attempted} sent=${summary.sent.size} failed=${summary.failed.size}")
        if (summary.failed.isNotEmpty()) {
            throw AssertionError("Group failed message ids: ${summary.failed.map { it.id }}")
        }
    }

    @Test fun send_real_network_repeatable_community() = runBlocking {
        val recipient = Address.fromSerialized("community://https%3A%2F%2Ftest-chat.session.codes?room=testing-all-the-things")

        // ensure thread exists
        val threadId = storage.getOrCreateThreadIdFor(recipient)

        val job = debugTextSendJobFactory.create(
            threadId = threadId,
            address = recipient,
            count = COMMUNITY_MESSAGE_COUNT,
            delayBetweenMessagesMs = DEFAULT_DELAY_MS,
            prefix = "test community"
        )

        val messageIds = withTimeout(DEFAULT_EXECUTION_TIMEOUT_MS) {
            job.executeAndReturnMessageIds(dispatcherName = "instrumentation-test-open-group")
        }

        val summary = awaitTerminalStates(
            ids = messageIds,
            timeoutMs = DEFAULT_AWAIT_TIMEOUT_MS,
            pollMs = POLL_INTERVAL_MS
        )

        Log.i("NetworkSendTest", "Community attempted=${summary.attempted} sent=${summary.sent.size} failed=${summary.failed.size}")
        if (summary.failed.isNotEmpty()) {
            throw AssertionError("Community failed message ids: ${summary.failed.map { it.id }}")
        }
    }
}
package org.thoughtcrime.securesms.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.NoOpLogger
import org.thoughtcrime.securesms.api.snode.AlterTtlApi
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.model.MessageId
import kotlin.time.Duration.Companion.minutes

/**
 * A message's sync can land minutes after it was sent, on a retry or after a restart. Here: a
 * five-minute disappear-after-send message whose sync lands four minutes after it was sent.
 */
class ExpiringMessageManagerSyncTest {
    private val sentTimestamp = 1_700_000_000_000L
    private val timer = 5.minutes.inWholeMilliseconds
    private val now = sentTimestamp + 4.minutes.inWholeMilliseconds

    private val smsDatabase = mockk<SmsDatabase>(relaxed = true)

    /** The expiry each shortening request asked our own swarm for. */
    private val requestedExpiries = mutableListOf<Long>()

    private val alterTtlApiFactory = mockk<AlterTtlApi.Factory> {
        every { create(any(), any(), any(), any()) } answers {
            requestedExpiries += arg<Long>(3)
            mockk(relaxed = true)
        }
    }

    @Before
    fun setUp() {
        Log.initialize(NoOpLogger)
    }

    @Test
    fun `a late sync leaves the timer alone and expires the copy with the linked devices`() = runTest {
        manager(this).onMessageSynced(message())
        advanceUntilIdle()

        verify(exactly = 0) { smsDatabase.markExpireStarted(any(), any()) }
        assertEquals(listOf(sentTimestamp + timer), requestedExpiries)
    }

    /** The same message landing as a first send starts its timer at that moment. */
    @Test
    fun `a first send starts the timer when it lands`() = runTest {
        manager(this).onMessageSent(message())
        advanceUntilIdle()

        verify(exactly = 1) { smsDatabase.markExpireStarted(42L, now) }
        assertEquals(listOf(now + timer), requestedExpiries)
    }

    @Test
    fun `a sync of a message with no timer shortens nothing`() = runTest {
        manager(this).onMessageSynced(message().apply { expiryMode = ExpiryMode.NONE })
        advanceUntilIdle()

        assertEquals(emptyList<Long>(), requestedExpiries)
    }

    private fun message() = VisibleMessage(syncTarget = "05" + "bb".repeat(32), text = "hi").apply {
        id = MessageId(42L, false)
        sentTimestamp = this@ExpiringMessageManagerSyncTest.sentTimestamp
        serverHash = "own-swarm-hash"
        expiryMode = ExpiryMode.AfterSend(timer / 1000)
    }

    private fun manager(scope: CoroutineScope): ExpiringMessageManager {
        val storage = mockk<Storage> {
            every { getUserPublicKey() } returns "05" + "aa".repeat(32)
            every { getUserED25519KeyPair() } returns KeyPair(ByteArray(32), ByteArray(64))
        }

        return ExpiringMessageManager(
            smsDatabase = smsDatabase,
            mmsDatabase = mockk(relaxed = true),
            clock = mockk { every { currentTimeMillis() } returns now },
            storage = { storage },
            loginStateRepository = mockk(relaxed = true),
            recipientRepository = mockk(relaxed = true),
            alterTtlApiFactory = alterTtlApiFactory,
            swarmApiExecutor = mockk(relaxed = true),
            scope = scope,
        )
    }
}

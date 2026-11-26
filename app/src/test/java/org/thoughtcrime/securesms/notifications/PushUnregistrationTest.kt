package org.thoughtcrime.securesms.notifications

import androidx.work.WorkerParameters
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.GroupInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.sending_receiving.notifications.UnsubscribeResponse
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigs
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.PushRegistrationDatabase
import org.thoughtcrime.securesms.database.PushRegistrationDatabase.GroupAuth
import org.thoughtcrime.securesms.database.PushRegistrationDatabase.RegistrationState
import org.thoughtcrime.securesms.database.PushRegistrationDatabase.RegistrationWithState
import org.thoughtcrime.securesms.dependencies.ConfigFactory

/**
 * A group another device removes is gone from the user-groups config before its push unregistration
 * runs. Synthetic keys and IDs only.
 */
class PushUnregistrationTest {
    private val groupId = AccountId("03${"11".repeat(32)}")
    private val otherGroupId = AccountId("03${"22".repeat(32)}")
    private val input = PushRegistrationDatabase.Input(pushToken = "token")
    private val adminKey = ByteArray(64) { 1 }
    private val authData = ByteArray(100) { 2 }

    private val configFactory = mockk<ConfigFactoryProtocol>()
    private val pushRegistrationDatabase = mockk<PushRegistrationDatabase>(relaxed = true)
    private val serverApiExecutor = mockk<ServerApiExecutor>()
    private val pushUnregisterApiFactory = mockk<PushUnregisterApi.Factory>()
    private val logged = mutableListOf<String>()

    /** The auth each unregister request was built with, in request order. */
    private val signedWith = mutableListOf<SwarmAuth>()

    @Before
    fun setUp() {
        Log.initialize(object : Log.Logger() {
            override fun v(tag: String?, message: String?, t: Throwable?) {}
            override fun d(tag: String?, message: String?, t: Throwable?) {}
            override fun i(tag: String?, message: String?, t: Throwable?) {}
            override fun w(tag: String?, message: String?, t: Throwable?) { logged += message.orEmpty() }
            override fun e(tag: String?, message: String?, t: Throwable?) { logged += message.orEmpty() }
            override fun wtf(tag: String?, message: String?, t: Throwable?) {}
            override fun blockUntilAllWritesFinished() {}
        })

        every { pushRegistrationDatabase.getNextProcessTime(any()) } returns null
        coEvery { serverApiExecutor.send(any(), any()) } returns UnsubscribeResponse(success = true)

        val swarmAuth = slot<SwarmAuth>()
        every { pushUnregisterApiFactory.create(any(), capture(swarmAuth)) } answers {
            signedWith += swarmAuth.captured
            mockk(relaxed = true)
        }
    }

    private fun pendingUnregister(accountId: AccountId, groupAuth: GroupAuth?) = RegistrationWithState(
        accountId = accountId.hexString,
        input = input,
        state = RegistrationState.PendingUnregister,
        groupAuth = groupAuth,
    )

    private suspend fun TestScope.runWorker(vararg unregister: RegistrationWithState) {
        every { pushRegistrationDatabase.getPendingRegistrationWork(any(), any()) } returns
                PushRegistrationDatabase.PendingRegistrationWork(
                    register = emptyList(),
                    unregister = unregister.toList(),
                )

        PushRegistrationWorker(
            context = mockk(relaxed = true),
            params = mockk<WorkerParameters>(relaxed = true),
            storage = mockk<StorageProtocol>(),
            pushRegistrationDatabase = pushRegistrationDatabase,
            configFactory = configFactory,
            loginStateRepository = mockk<LoginStateRepository>(),
            semaphore = Semaphore(1),
            // No batch key, so every request goes straight to the executor
            pushApiBatcher = mockk { every { batchKey(any()) } returns null },
            serverApiExecutor = serverApiExecutor,
            scope = backgroundScope,
            pushRegisterApiFactory = mockk(),
            pushUnregisterApiFactory = pushUnregisterApiFactory,
        ).doWork()
    }

    private fun removedRows(): List<PushRegistrationDatabase.Registration> {
        val removed = slot<Collection<PushRegistrationDatabase.Registration>>()
        verify { pushRegistrationDatabase.removeRegistrations(capture(removed)) }
        return removed.captured.toList()
    }

    @Test
    fun `a group removed by another device is unsubscribed with the member auth kept on its row`() = runTest {
        val storedAuth = mockk<SwarmAuth>()
        every { configFactory.getGroupAuth(groupId) } returns null
        every { configFactory.createGroupAuth(groupId, null, any()) } returns storedAuth

        runWorker(pendingUnregister(groupId, GroupAuth.of(adminKey = null, authData = authData)))

        verify {
            configFactory.createGroupAuth(groupId, null, match { it.contentEquals(authData) })
        }
        assertEquals(listOf(storedAuth), signedWith)
        coVerify(exactly = 1) { serverApiExecutor.send(any(), any()) }
        assertEquals(listOf(groupId.hexString), removedRows().map { it.accountId })
    }

    @Test
    fun `a group removed by another device is unsubscribed with the admin key kept on its row`() = runTest {
        val storedAuth = mockk<SwarmAuth>()
        every { configFactory.getGroupAuth(groupId) } returns null
        every { configFactory.createGroupAuth(groupId, any(), null) } returns storedAuth

        runWorker(pendingUnregister(groupId, GroupAuth.of(adminKey = adminKey, authData = null)))

        verify {
            configFactory.createGroupAuth(groupId, match { it.contentEquals(adminKey) }, null)
        }
        assertEquals(listOf(storedAuth), signedWith)
        coVerify(exactly = 1) { serverApiExecutor.send(any(), any()) }
    }

    @Test
    fun `a group still in the config is unsubscribed with its own auth`() = runTest {
        val configAuth = mockk<SwarmAuth>()
        every { configFactory.getGroupAuth(groupId) } returns configAuth

        runWorker(pendingUnregister(groupId, GroupAuth.of(adminKey = null, authData = authData)))

        assertEquals(listOf(configAuth), signedWith)
        verify(exactly = 0) { configFactory.createGroupAuth(any(), any(), any()) }
        coVerify(exactly = 1) { serverApiExecutor.send(any(), any()) }
        assertEquals(listOf(groupId.hexString), removedRows().map { it.accountId })
    }

    @Test
    fun `an unregistration nothing can sign is logged and removed without a request`() = runTest {
        // otherGroupId is the positive control: it proves the worker reached the sending stage
        val otherAuth = mockk<SwarmAuth>()
        every { configFactory.getGroupAuth(groupId) } returns null
        every { configFactory.getGroupAuth(otherGroupId) } returns otherAuth

        runWorker(
            pendingUnregister(groupId, groupAuth = null),
            pendingUnregister(otherGroupId, groupAuth = null),
        )

        assertEquals(listOf(otherAuth), signedWith)
        coVerify(exactly = 1) { serverApiExecutor.send(any(), any()) }
        assertEquals(
            setOf(groupId.hexString, otherGroupId.hexString),
            removedRows().map { it.accountId }.toSet()
        )
        assertTrue(logged.toString(), logged.any { it.startsWith("Push unregistration skipped") })
    }

    @Test
    fun `the skip log carries no key material or group ID`() = runTest {
        every { configFactory.getGroupAuth(groupId) } returns null
        every { configFactory.createGroupAuth(groupId, any(), any()) } returns null

        runWorker(pendingUnregister(groupId, GroupAuth.of(adminKey = adminKey, authData = authData)))

        val skipLog = logged.single { it.startsWith("Push unregistration skipped") }
        assertFalse(skipLog, skipLog.contains(adminKey.toHexString()))
        assertFalse(skipLog, skipLog.contains(authData.toHexString()))
        assertFalse(skipLog, skipLog.contains(groupId.hexString))
    }

    @Test
    fun `each group's registration carries the credentials from its user-groups entry`() {
        val member = closedGroup(groupId, adminKey = null, authData = authData)
        val admin = closedGroup(otherGroupId, adminKey = adminKey, authData = null)
        val userGroups = mockk<ReadableUserGroupsConfig> {
            every { allClosedGroupInfo() } returns listOf(member, admin)
        }
        val configFactory = mockk<ConfigFactory> {
            every { dangerouslyAccessUserConfigs() } returns
                    Pair(mockk<UserConfigs> { every { this@mockk.userGroups } returns userGroups }, {})
        }

        val registrations = PushRegistrationHandler(
            configFactory = configFactory,
            prefs = mockk(),
            tokenFetcher = mockk(),
            context = mockk(),
            semaphore = Semaphore(1),
            pushRegistrationDatabase = pushRegistrationDatabase,
        ).desiredSubscriptions(localNumber = "05${"33".repeat(32)}", token = input.pushToken)
            .associateBy { it.accountId }

        assertArrayEquals(authData, registrations.getValue(groupId.hexString).groupAuth!!.authData)
        assertArrayEquals(adminKey, registrations.getValue(otherGroupId.hexString).groupAuth!!.adminKey)
        assertEquals(null, registrations.getValue("05${"33".repeat(32)}").groupAuth)
    }

    private fun closedGroup(id: AccountId, adminKey: ByteArray?, authData: ByteArray?) =
        mockk<GroupInfo.ClosedGroupInfo> {
            every { groupAccountId } returns id.hexString
            every { this@mockk.adminKey } returns adminKey?.let(::Bytes)
            every { this@mockk.authData } returns authData?.let(::Bytes)
            every { shouldPoll } returns true
        }
}

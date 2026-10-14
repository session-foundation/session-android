package org.thoughtcrime.securesms.groups

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.MutableUserGroupsConfig
import network.loki.messenger.libsession_util.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.GroupInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import network.loki.messenger.libsession_util.util.KeyPair
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupScope
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.thoughtcrime.securesms.api.snode.DeleteMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.session.libsession.utilities.MutableUserConfigs
import org.session.libsession.utilities.UserConfigs
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.util.MockLoggingRule

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 36)
class GroupManagerV2ImplTest {

    @get:Rule
    val logRule = MockLoggingRule()

    private val groupId = AccountId("03" + "ab".repeat(32))
    private val inviter = AccountId("05" + "cd".repeat(32))

    private val joinedGroup = GroupInfo.ClosedGroupInfo(
        groupAccountId = groupId.hexString,
        adminKey = null,
        authData = Bytes(ByteArray(100) { 7 }),
        priority = PRIORITY_VISIBLE,
        invited = false,
        name = "Synthetic group",
        destroyed = false,
        joinedAtSecs = 1_700_000_000L,
        kicked = false,
    )

    private val mutableUserGroups: MutableUserGroupsConfig = mock()

    private lateinit var configFactory: ConfigFactory
    private lateinit var messageSender: MessageSender
    private lateinit var swarmApiExecutor: SwarmApiExecutor

    /**
     * [existingGroup] is what the user's own config already holds for this group, or null when the
     * invitation is genuinely the first one.
     */
    private fun manager(
        scope: CoroutineScope,
        existingGroup: GroupInfo.ClosedGroupInfo?,
    ): GroupManagerV2Impl {
        val readableUserGroups: ReadableUserGroupsConfig = mock {
            on { getClosedGroup(groupId.hexString) } doReturn existingGroup
        }

        val userConfigs: UserConfigs = mock { on { userGroups } doReturn readableUserGroups }
        val mutableUserConfigs: MutableUserConfigs = mock { on { userGroups } doReturn mutableUserGroups }

        configFactory = mock {
            on { dangerouslyAccessUserConfigs() } doReturn (userConfigs to {})
            on { dangerouslyAccessMutableUserConfigs() } doReturn (mutableUserConfigs to {})
        }

        val notApproved: Recipient = mock { on { approved } doReturn false }
        val recipientRepository: RecipientRepository = mock {
            onBlocking { getRecipient(any()) } doReturn notApproved
        }

        messageSender = mock()
        swarmApiExecutor = mock {
            onBlocking { send(any(), any()) } doReturn mock<DeleteMessageApi.SuccessResponse>()
        }

        // userAuth is an extension property, so it is satisfied through what it reads rather than
        // stubbed: without it the invitation acknowledgement cannot delete the invite message.
        val storage: StorageProtocol = mock {
            on { getUserPublicKey() } doReturn "05" + "ef".repeat(32)
            on { getUserED25519KeyPair() } doReturn KeyPair(
                Bytes(ByteArray(32) { 1 }),
                Bytes(ByteArray(64) { 2 }),
            )
        }

        return GroupManagerV2Impl(
            storage = storage,
            configFactory = configFactory,
            mmsSmsDatabase = mock(),
            lokiDatabase = mock(),
            application = mock(),
            clock = mock(),
            messageDataProvider = mock(),
            lokiAPIDatabase = mock(),
            receivedMessageHashDatabase = mock(),
            configUploader = mock(),
            scope = GroupScope(scope),
            groupPollerManager = mock(),
            recipientRepository = recipientRepository,
            messageSender = messageSender,
            inviteContactJobFactory = mock(),
            swarmApiExecutor = swarmApiExecutor,
            deleteMessageApiFactory = mock { on { create(any(), any()) } doReturn mock() },
            storeSnodeMessageApiFactory = mock(),
            unrevokeSubKeyApiFactory = mock(),
            batchApiFactory = mock(),
            jobQueue = mock(),
        )
    }

    private suspend fun GroupManagerV2Impl.deliverInvitation() {
        handleInvitation(
            groupId = groupId,
            groupName = "Synthetic group",
            authData = ByteArray(100) { 9 },
            inviter = inviter,
            inviterName = "Inviter",
            inviteMessageHash = "synthetic-hash",
            inviteMessageTimestamp = 1_700_000_500_000L,
        )
    }

    @Test
    fun `a second invitation leaves a joined group untouched`() = runTest {
        val manager = manager(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            existingGroup = joinedGroup,
        )

        manager.deliverInvitation()

        verify(configFactory, never()).dangerouslyAccessMutableUserConfigs()
        verify(mutableUserGroups, never()).set(any())
    }

    @Test
    fun `a first invitation is written to the user's groups`() = runTest {
        val manager = manager(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            existingGroup = null,
        )

        manager.deliverInvitation()

        val written = argumentCaptor<GroupInfo>()
        verify(mutableUserGroups).set(written.capture())
        val group = written.firstValue as GroupInfo.ClosedGroupInfo
        assertThat(group.groupAccountId).isEqualTo(groupId.hexString)
        assertThat(group.invited).isTrue()
    }

    @Test
    fun `a second invitation is still answered, so the admin can stop showing us as invited`() = runTest {
        val manager = manager(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            existingGroup = joinedGroup,
        )

        manager.deliverInvitation()

        // Our membership is untouched...
        verify(configFactory, never()).dangerouslyAccessMutableUserConfigs()
        // ...and the invite response goes out anyway: it is sent once on approval with its failure
        // swallowed, so a re-invite is the admin's only way to recover a lost one.
        verify(messageSender).sendNonDurably(any(), any<Destination.ClosedGroup>(), eq(false))
        // The invitation is dropped from our swarm, as approving one does.
        verify(swarmApiExecutor).send(any(), any())
    }

    @Test
    fun `an admin member answers a second invitation without sending a response`() = runTest {
        val manager = manager(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            existingGroup = joinedGroup.copy(adminKey = Bytes(ByteArray(64) { 3 })),
        )

        manager.deliverInvitation()

        // An admin's membership is not established by an invite response - they write it themselves.
        verify(messageSender, never()).sendNonDurably(any(), any<Destination>(), any())
        verify(swarmApiExecutor).send(any(), any())
    }
}

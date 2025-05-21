package org.thoughtcrime.securesms.groups

import android.content.Context
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.Namespace
import network.loki.messenger.libsession_util.util.Bytes.Companion.toBytes
import network.loki.messenger.libsession_util.util.Conversation
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.GroupMember
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.groups.GroupInviteException
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.groups.GroupScope
import org.session.libsession.messaging.jobs.InviteContactsJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.visible.Profile
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.MessageAuthentication.buildDeleteMemberContentSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildInfoChangeSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildMemberChangeSignature
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.model.BatchResponse
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.waitUntilGroupConfigsPushed
import org.session.libsignal.protos.SignalServiceProtos.DataMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateDeleteMemberContentMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateInfoChangeMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateInviteResponseMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMemberChangeMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.configs.ConfigUploader
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.util.SessionMetaProtocol
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GroupManagerV2Impl"

@Singleton
class GroupManagerV2Impl @Inject constructor(
    private val storage: StorageProtocol,
    private val configFactory: ConfigFactory,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val lokiDatabase: LokiMessageDatabase,
    private val threadDatabase: ThreadDatabase,
    private val profileManager: SSKEnvironment.ProfileManagerProtocol,
    @ApplicationContext val application: Context,
    private val clock: SnodeClock,
    private val messageDataProvider: MessageDataProvider,
    private val lokiAPIDatabase: LokiAPIDatabase,
    private val configUploader: ConfigUploader,
    private val scope: GroupScope,
    private val groupPollerManager: GroupPollerManager,
) : GroupManagerV2 {
    private val dispatcher = Dispatchers.Default

    /**
     * Require admin access to a group, and return the admin key.
     *
     * @throws IllegalArgumentException if the group does not exist or no admin key is found.
     */
    private fun requireAdminAccess(group: AccountId): ByteArray {
        return checkNotNull(
            configFactory.getGroup(group)
                ?.adminKey
                ?.data
                ?.takeIf { it.isNotEmpty() }
        ) { "Only admin is allowed to invite members" }
    }

    override suspend fun createGroup(
        groupName: String,
        groupDescription: String,
        members: Set<AccountId>
    ): Recipient = withContext(dispatcher) {
        val ourAccountId =
            requireNotNull(storage.getUserPublicKey()) { "Our account ID is not available" }
        val ourProfile = storage.getUserProfile()

        val groupCreationTimestamp = clock.currentTimeMills()

        // Create a group in the user groups config
        val group = configFactory.withUserConfigs { configs ->
            configs.userGroups.createGroup()
                .copy(
                    name = groupName,
                    joinedAtSecs = TimeUnit.MILLISECONDS.toSeconds(groupCreationTimestamp)
                )
        }

        val adminKey = checkNotNull(group.adminKey?.data) { "Admin key is null for new group creation." }
        val groupId = AccountId(group.groupAccountId)

        val memberAsRecipients = members.map {
            Recipient.from(application, Address.fromSerialized(it.hexString), false)
        }

        try {
            val newGroupConfigs = configFactory.createGroupConfigs(groupId, adminKey)

            // Update group's information
            newGroupConfigs.groupInfo.setName(groupName)
            newGroupConfigs.groupInfo.setDescription(groupDescription)

            // Add members
            for (member in memberAsRecipients) {
                newGroupConfigs.groupMembers.set(
                    newGroupConfigs.groupMembers.getOrConstruct(member.address.toString()).apply {
                        setName(member.name)
                        setProfilePic(member.profileAvatar?.let { url ->
                            member.profileKey?.let { key -> UserPic(url, key) }
                        } ?: UserPic.DEFAULT)
                    }
                )
            }

            // Add ourselves as admin
            newGroupConfigs.groupMembers.set(
                newGroupConfigs.groupMembers.getOrConstruct(ourAccountId).apply {
                    setName(ourProfile.displayName.orEmpty())
                    setProfilePic(ourProfile.profilePicture ?: UserPic.DEFAULT)
                    setPromotionAccepted()
                }
            )

            // Manually re-key to prevent issue with linked admin devices
            newGroupConfigs.rekey()

            // Make sure the initial group configs are pushed
            configUploader.pushGroupConfigsChangesIfNeeded(adminKey = adminKey, groupId = groupId, groupConfigAccess = { access ->
                access(newGroupConfigs)
            })

            // Now we can save it to our factory for further access
            configFactory.saveGroupConfigs(groupId, newGroupConfigs)

            // Once the group configs are created successfully, we add it to our config
            configFactory.withMutableUserConfigs { configs ->
                configs.userGroups.set(group)

                configs.convoInfoVolatile.set(
                    Conversation.ClosedGroup(
                        groupId.hexString,
                        groupCreationTimestamp,
                        false
                    )
                )
            }

            // Make sure a thread exists at this point as we will need it for successfully sending
            // control messages. Normally the thread will be created automatically but it's done
            // in the background. We have no way to know about the state of that async background process
            // hence we will need to create it manually here.
            check(storage.getOrCreateThreadIdFor(Address.fromSerialized(groupId.hexString)) != -1L) {
                "Failed to create a thread for the group"
            }

            val recipient =
                Recipient.from(application, Address.fromSerialized(groupId.hexString), false)

            // Apply various data locally
            profileManager.setName(application, recipient, groupName)
            storage.setRecipientApprovedMe(recipient, true)
            storage.setRecipientApproved(recipient, true)

            // Invite members
            JobQueue.shared.add(
                InviteContactsJob(
                    groupSessionId = groupId.hexString,
                    memberSessionIds = members.map { it.hexString }.toTypedArray()
                )
            )

            // Also send a group update message
            sendGroupUpdateForAddingMembers(groupId, adminKey, members)

            recipient
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create group", e)

            throw e
        }
    }


    override suspend fun inviteMembers(
        group: AccountId,
        newMembers: List<AccountId>,
        shareHistory: Boolean,
        isReinvite: Boolean
    ): Unit = scope.launchAndWait(group, "Invite members") {
        val adminKey = requireAdminAccess(group)
        val groupAuth = OwnedSwarmAuth.ofClosedGroup(group, adminKey)

        val batchRequests = mutableListOf<SnodeAPI.SnodeBatchRequestInfo>()

        // Construct the new members in our config
        val subAccountTokens = configFactory.withMutableGroupConfigs(group) { configs ->
            // Construct the new members in the config
            for (newMember in newMembers) {
                val toSet = configs.groupMembers.get(newMember.hexString)
                    ?.also { existing ->
                        val status = configs.groupMembers.status(existing)
                        if (status == GroupMember.Status.INVITE_FAILED || status == GroupMember.Status.INVITE_SENT) {
                            existing.setSupplement(shareHistory)
                        }
                    }
                    ?: configs.groupMembers.getOrConstruct(newMember.hexString).also { member ->
                        val contact = configFactory.withUserConfigs { configs ->
                            configs.contacts.get(newMember.hexString)
                        }

                        member.setName(contact?.name.orEmpty())
                        member.setProfilePic(contact?.profilePicture ?: UserPic.DEFAULT)
                        member.setSupplement(shareHistory)
                    }

                toSet.setInvited()
                configs.groupMembers.set(toSet)
            }

            if (shareHistory) {
                val memberKey = configs.groupKeys.supplementFor(newMembers.map { it.hexString })
                batchRequests.add(
                    SnodeAPI.buildAuthenticatedStoreBatchInfo(
                        namespace = Namespace.GROUP_KEYS(),
                        message = SnodeMessage(
                            recipient = group.hexString,
                            data = Base64.encodeBytes(memberKey),
                            ttl = SnodeMessage.CONFIG_TTL,
                            timestamp = clock.currentTimeMills(),
                        ),
                        auth = groupAuth,
                    )
                )
            }

            configs.rekey()
            newMembers.map { configs.groupKeys.getSubAccountToken(it.hexString) }
        }

        // Call un-revocate API on new members, in case they have been removed before
        batchRequests += SnodeAPI.buildAuthenticatedUnrevokeSubKeyBatchRequest(
            groupAdminAuth = groupAuth,
            subAccountTokens = subAccountTokens
        )

        // Call the API
        try {
            val swarmNode = SnodeAPI.getSingleTargetSnode(group.hexString).await()
            val response = SnodeAPI.getBatchResponse(swarmNode, group.hexString, batchRequests)

            // Make sure every request is successful
            response.requireAllRequestsSuccessful("Failed to invite members")

            // Wait for the group configs to be pushed
            configFactory.waitUntilGroupConfigsPushed(group)
        } catch (e: Exception) {
            // Update every member's status to "invite failed" and return group name
            val groupName = configFactory.withMutableGroupConfigs(group) { configs ->
                for (newMember in newMembers) {
                    configs.groupMembers.get(newMember.hexString)?.apply {
                        setInviteFailed()
                        configs.groupMembers.set(this)
                    }
                }

                configs.groupInfo.getName().orEmpty()
            }

            Log.w(TAG, "Failed to invite members to group $group", e)

            throw GroupInviteException(
                isPromotion = false,
                inviteeAccountIds = newMembers.map { it.hexString },
                groupName = groupName,
                underlying = e
            )
        } finally {
            // Send a group update message to the group telling members someone has been invited
            if (!isReinvite) {
                sendGroupUpdateForAddingMembers(group, adminKey, newMembers)
            }
        }

        // Send the invitation message to the new members
        JobQueue.shared.add(
            InviteContactsJob(
                group.hexString,
                newMembers.map { it.hexString }.toTypedArray()
            )
        )
    }

    /**
     * Send a group update message to the group telling members someone has been invited.
     */
    private fun sendGroupUpdateForAddingMembers(
        group: AccountId,
        adminKey: ByteArray,
        newMembers: Collection<AccountId>,
    ) {
        val timestamp = clock.currentTimeMills()
        val signature = SodiumUtilities.sign(
            buildMemberChangeSignature(GroupUpdateMemberChangeMessage.Type.ADDED, timestamp),
            adminKey
        )

        val updatedMessage = GroupUpdated(
            GroupUpdateMessage.newBuilder()
                .setMemberChangeMessage(
                    GroupUpdateMemberChangeMessage.newBuilder()
                        .addAllMemberSessionIds(newMembers.map { it.hexString })
                        .setType(GroupUpdateMemberChangeMessage.Type.ADDED)
                        .setAdminSignature(ByteString.copyFrom(signature))
                )
                .build()
        ).apply { this.sentTimestamp = timestamp }

        storage.insertGroupInfoChange(updatedMessage, group)

        MessageSender.send(updatedMessage, Address.fromSerialized(group.hexString))
    }

    override suspend fun removeMembers(
        groupAccountId: AccountId,
        removedMembers: List<AccountId>,
        removeMessages: Boolean
    ) {
        val adminKey = requireAdminAccess(groupAccountId)

        // Update the config to mark this member as "removed"
        flagMembersForRemoval(
            group = groupAccountId,
            groupAdminKey = adminKey,
            members = removedMembers,
            alsoRemoveMembersMessage = removeMessages,
        )

        val timestamp = clock.currentTimeMills()
        val signature = SodiumUtilities.sign(
            buildMemberChangeSignature(
                GroupUpdateMemberChangeMessage.Type.REMOVED,
                timestamp
            ),
            adminKey
        )

        val updateMessage = GroupUpdateMessage.newBuilder()
            .setMemberChangeMessage(
                GroupUpdateMemberChangeMessage.newBuilder()
                    .addAllMemberSessionIds(removedMembers.map { it.hexString })
                    .setType(GroupUpdateMemberChangeMessage.Type.REMOVED)
                    .setAdminSignature(ByteString.copyFrom(signature))
            )
            .build()
        val message = GroupUpdated(
            updateMessage
        ).apply { sentTimestamp = timestamp }

        MessageSender.send(message, Address.fromSerialized(groupAccountId.hexString))
        storage.insertGroupInfoChange(message, groupAccountId)
    }

    override suspend fun removeMemberMessages(
        groupAccountId: AccountId,
        members: List<AccountId>
    ): Unit = scope.launchAndWait(groupAccountId, "Remove member messages") {
        val messagesToDelete = mutableListOf<String>()

        val threadId = storage.getThreadId(Address.fromSerialized(groupAccountId.hexString))
        if (threadId != null) {
            for (member in members) {
                for (msg in mmsSmsDatabase.getUserMessages(threadId, member.hexString)) {
                    val serverHash = lokiDatabase.getMessageServerHash(msg.messageId)
                    if (serverHash != null) {
                        messagesToDelete.add(serverHash)
                    }
                }

                storage.deleteMessagesByUser(threadId, member.hexString)
            }
        }

        if (messagesToDelete.isEmpty()) {
            return@launchAndWait
        }

        val groupAdminAuth = configFactory.getGroup(groupAccountId)?.adminKey?.data?.let {
            OwnedSwarmAuth.ofClosedGroup(groupAccountId, it)
        } ?: return@launchAndWait

        SnodeAPI.deleteMessage(groupAccountId.hexString, groupAdminAuth, messagesToDelete)
    }

    override suspend fun clearAllMessagesForEveryone(groupAccountId: AccountId, deletedHashes: List<String?>) {
        // only admins can perform these tasks
        val groupAdminAuth = configFactory.getGroup(groupAccountId)?.adminKey?.data?.let {
            OwnedSwarmAuth.ofClosedGroup(groupAccountId, it)
        } ?: return

        // change the delete_before
        configFactory.withMutableGroupConfigs(groupAccountId) { configs ->
            configs.groupInfo.setDeleteBefore(clock.currentTimeSeconds())
        }

        // remove messages from swarm SnodeAPI.deleteMessage
        val cleanedHashes: List<String> = deletedHashes.filter { !it.isNullOrEmpty() }.filterNotNull()
        if(cleanedHashes.isNotEmpty()) SnodeAPI.deleteMessage(groupAccountId.hexString, groupAdminAuth, cleanedHashes)
    }

    override suspend fun handleMemberLeftMessage(memberId: AccountId, group: AccountId) = scope.launchAndWait(group, "Handle member left message") {
        val closedGroup = configFactory.getGroup(group) ?: return@launchAndWait
        val groupAdminKey = closedGroup.adminKey?.data

        if (groupAdminKey != null) {
            flagMembersForRemoval(
                group = group,
                groupAdminKey = groupAdminKey,
                members = listOf(memberId),
                alsoRemoveMembersMessage = false,
            )
        }
    }

    override suspend fun leaveGroup(groupId: AccountId) {
        scope.launchAndWait(groupId, "Leave group") {
            withContext(SupervisorJob()) {
                val group = configFactory.getGroup(groupId)

                storage.insertGroupInfoLeaving(groupId)

                try {
                    if (group?.destroyed != true) {
                        // Only send the left/left notification group message when we are not kicked and we are not the only admin (only admin has a special treatment)
                        val weAreTheOnlyAdmin = configFactory.withGroupConfigs(groupId) { config ->
                            val allMembers = config.groupMembers.all()
                            allMembers.count { it.admin } == 1 &&
                                    allMembers.first { it.admin }
                                        .accountId() == storage.getUserPublicKey()
                        }

                        if (group != null && !group.kicked && !weAreTheOnlyAdmin) {
                            val address = Address.fromSerialized(groupId.hexString)

                            // Always send a "XXX left" message to the group if we can
                            MessageSender.send(
                                GroupUpdated(
                                    GroupUpdateMessage.newBuilder()
                                        .setMemberLeftNotificationMessage(DataMessage.GroupUpdateMemberLeftNotificationMessage.getDefaultInstance())
                                        .build()
                                ),
                                address
                            )

                            // If we are not the only admin, send a left message for other admin to handle the member removal
                            MessageSender.send(
                                GroupUpdated(
                                    GroupUpdateMessage.newBuilder()
                                        .setMemberLeftMessage(DataMessage.GroupUpdateMemberLeftMessage.getDefaultInstance())
                                        .build()
                                ),
                                address,
                            )
                        }

                        // If we are the only admin, leaving this group will destroy the group
                        if (weAreTheOnlyAdmin) {
                            configFactory.withMutableGroupConfigs(groupId) { configs ->
                                configs.groupInfo.destroyGroup()
                            }

                            // Must wait until the config is pushed, otherwise if we go through the rest
                            // of the code it will destroy the conversation, destroying the necessary configs
                            // along the way, we won't be able to push the "destroyed" state anymore.
                            configFactory.waitUntilGroupConfigsPushed(groupId)
                        }
                    }

                    // Delete conversation and group configs
                    storage.getThreadId(Address.fromSerialized(groupId.hexString))
                        ?.let(storage::deleteConversation)
                    configFactory.removeGroup(groupId)
                    lokiAPIDatabase.clearLastMessageHashes(groupId.hexString)
                    lokiAPIDatabase.clearReceivedMessageHashValues(groupId.hexString)
                } catch (e: Exception) {
                    storage.insertGroupInfoErrorQuit(groupId)
                    throw e
                } finally {
                    storage.deleteGroupInfoMessages(groupId, UpdateMessageData.Kind.GroupLeaving::class.java)
                }
            }
        }
    }

    override suspend fun promoteMember(
        group: AccountId,
        members: List<AccountId>,
        isRepromote: Boolean
    ): Unit = scope.launchAndWait(group, "Promote member") {
        withContext(SupervisorJob()) {
            val adminKey = requireAdminAccess(group)
            val groupName = configFactory.withMutableGroupConfigs(group) { configs ->
                // Update the group member's promotion status
                members.asSequence()
                    .mapNotNull { configs.groupMembers.get(it.hexString) }
                    .onEach(GroupMember::setPromoted)
                    .forEach(configs.groupMembers::set)

                configs.groupInfo.getName()
            }

            // Build a group update message to the group telling members someone has been promoted
            val timestamp = clock.currentTimeMills()
            val signature = SodiumUtilities.sign(
                buildMemberChangeSignature(GroupUpdateMemberChangeMessage.Type.PROMOTED, timestamp),
                adminKey
            )

            val message = GroupUpdated(
                GroupUpdateMessage.newBuilder()
                    .setMemberChangeMessage(
                        GroupUpdateMemberChangeMessage.newBuilder()
                            .addAllMemberSessionIds(members.map { it.hexString })
                            .setType(GroupUpdateMemberChangeMessage.Type.PROMOTED)
                            .setAdminSignature(ByteString.copyFrom(signature))
                    )
                    .build()
            ).apply {
                sentTimestamp = timestamp
            }

            if (!isRepromote) {
                // Insert the message locally immediately so we can see the incoming change
                // The same message will be sent later to the group
                storage.insertGroupInfoChange(message, group)
            }

            // Send out the promote message to the members concurrently
            val promoteMessage = GroupUpdated(
                GroupUpdateMessage.newBuilder()
                    .setPromoteMessage(
                        DataMessage.GroupUpdatePromoteMessage.newBuilder()
                            .setGroupIdentitySeed(ByteString.copyFrom(adminKey).substring(0, 32))
                            .setName(groupName)
                    )
                    .build()
            )

            val promotionDeferred = members.associateWith { member ->
                async {
                    // The promotion message shouldn't be persisted to avoid being retried automatically
                    MessageSender.sendNonDurably(
                        message = promoteMessage,
                        address = Address.fromSerialized(member.hexString),
                        isSyncMessage = false,
                    ).await()
                }
            }

            // Wait and gather all the promote message sending result into a result map
            val promotedByMemberIDs = promotionDeferred
                .mapValues {
                    runCatching { it.value.await() }.isSuccess
                }

            // Update each member's status
            configFactory.withMutableGroupConfigs(group) { configs ->
                promotedByMemberIDs.asSequence()
                    .mapNotNull { (member, success) ->
                        configs.groupMembers.get(member.hexString)?.apply {
                            if (success) {
                                setPromotionSent()
                            } else {
                                setPromotionFailed()
                            }
                        }
                    }
                    .forEach(configs.groupMembers::set)
            }


            if (!isRepromote) {
                MessageSender.sendAndAwait(message, Address.fromSerialized(group.hexString))
            }
        }
    }
    /**
     * Mark this member as "removed" in the group config.
     *
     * [RemoveGroupMemberHandler] should be able to pick up the config changes and remove the member from the group.
     */
    private fun flagMembersForRemoval(
        group: AccountId,
        groupAdminKey: ByteArray, // Not used ATM required here for verification purpose
        members: List<AccountId>,
        alsoRemoveMembersMessage: Boolean,
    ) {
        configFactory.withMutableGroupConfigs(group) { configs ->
            for (member in members) {
                val memberConfig = configs.groupMembers.get(member.hexString)
                if (memberConfig != null) {
                    configs.groupMembers.set(memberConfig.apply {
                        setRemoved(alsoRemoveMembersMessage)
                    })
                }
            }
        }
    }

    override suspend fun respondToInvitation(groupId: AccountId, approved: Boolean) =
        scope.launchAndWait(groupId, "Respond to invitation") {
            val group = requireNotNull(
                configFactory.withUserConfigs { it.userGroups.getClosedGroup(groupId.hexString) }
            ) { "User groups config is not available" }

            val threadId =
                checkNotNull(storage.getThreadId(Address.fromSerialized(groupId.hexString))) {
                    "No thread has been created for the group"
                }

            val groupInviteMessageHash = lokiDatabase.groupInviteMessageHash(threadId)

            // Whether approved or not, delete the invite
            lokiDatabase.deleteGroupInviteReferrer(threadId)

            storage.clearMessages(threadId)

            if (approved) {
                approveGroupInvite(group, groupInviteMessageHash)
            } else {
                configFactory.withMutableUserConfigs {
                    it.userGroups.eraseClosedGroup(groupId.hexString)
                    it.convoInfoVolatile.eraseClosedGroup(groupId.hexString)
                }
                storage.deleteConversation(threadId)

                if (groupInviteMessageHash != null) {
                    val auth = requireNotNull(storage.userAuth)
                    SnodeAPI.deleteMessage(
                        publicKey = auth.accountId.hexString,
                        swarmAuth = auth,
                        serverHashes = listOf(groupInviteMessageHash)
                    )
                }
            }
        }

    private suspend fun approveGroupInvite(
        group: GroupInfo.ClosedGroupInfo,
        inviteMessageHash: String?
    ) {
        val key = requireNotNull(storage.getUserPublicKey()) {
            "Our account ID is not available"
        }

        // Clear the invited flag of the group in the config
        configFactory.withMutableUserConfigs { configs ->
            configs.userGroups.set(group.copy(
                invited = false,
                joinedAtSecs = TimeUnit.MILLISECONDS.toSeconds(clock.currentTimeMills())
            ))
        }

        // We need to wait until we have the first data polled from the poller, otherwise
        // we won't have the necessary configs to send invite response/or do anything else.
        // We can't hang on here forever if things don't work out, bail out if it's the case.
        withTimeout(20_000L) {
            // We must tell the poller to poll once, as we could have received this invitation
            // in the background where the poller isn't running
            val groupId = AccountId(group.groupAccountId)
            groupPollerManager.pollOnce(groupId)

            groupPollerManager.watchGroupPollingState(groupId)
                .filter { it.hadAtLeastOneSuccessfulPoll }
                .first()
        }

        val adminKey = group.adminKey?.data
        if (adminKey == null) {
            // Send an invite response to the group if we are invited as a regular member
            val inviteResponse = GroupUpdateInviteResponseMessage.newBuilder()
                .setIsApproved(true)
            val responseData = GroupUpdateMessage.newBuilder()
                .setInviteResponse(inviteResponse)
            val responseMessage = GroupUpdated(responseData.build(), profile = storage.getUserProfile())
            // this will fail the first couple of times :)
            MessageSender.sendNonDurably(
                responseMessage,
                Destination.ClosedGroup(group.groupAccountId),
                isSyncMessage = false
            )
        } else {
            // If we are invited as admin, we can just update the group info ourselves
            configFactory.withMutableGroupConfigs(AccountId(group.groupAccountId)) { configs ->
                configs.groupKeys.loadAdminKey(adminKey)

                configs.groupMembers.get(key)?.let { member ->
                    configs.groupMembers.set(member.apply {
                        setPromotionAccepted()
                    })
                }

                Unit
            }
        }

        // Delete the invite once we have approved
        if (inviteMessageHash != null) {
            val auth = requireNotNull(storage.userAuth)
            SnodeAPI.deleteMessage(
                publicKey = auth.accountId.hexString,
                swarmAuth = auth,
                serverHashes = listOf(inviteMessageHash)
            )
        }
    }

    override suspend fun handleInvitation(
        groupId: AccountId,
        groupName: String,
        authData: ByteArray,
        inviter: AccountId,
        inviterName: String?,
        inviteMessageHash: String,
        inviteMessageTimestamp: Long,
    ): Unit = scope.launchAndWait(groupId, "Handle invitation") {
        handleInvitation(
            groupId = groupId,
            groupName = groupName,
            authDataOrAdminSeed = authData,
            fromPromotion = false,
            inviter = inviter,
            inviterName = inviterName,
            inviteMessageTimestamp = inviteMessageTimestamp,
            inviteMessageHash = inviteMessageHash,
        )
    }

    override suspend fun handlePromotion(
        groupId: AccountId,
        groupName: String,
        adminKeySeed: ByteArray,
        promoter: AccountId,
        promoterName: String?,
        promoteMessageHash: String,
        promoteMessageTimestamp: Long,
    ): Unit = scope.launchAndWait(groupId, "Handle promotion") {
        val userAuth = requireNotNull(storage.userAuth) { "No current user available" }
        val group = configFactory.getGroup(groupId)

        if (group == null) {
            // If we haven't got the group in the config, it could mean that we haven't
            // processed the invitation, or the invitation message is lost. We'll need to
            // go through the invitation process again.
            handleInvitation(
                groupId = groupId,
                groupName = groupName,
                authDataOrAdminSeed = adminKeySeed,
                fromPromotion = true,
                inviter = promoter,
                inviterName = promoterName,
                inviteMessageTimestamp = promoteMessageTimestamp,
                inviteMessageHash = promoteMessageHash
            )
        } else {
            // If we have the group in the config, we can just update the admin key
            val adminKey = GroupInfo.ClosedGroupInfo.adminKeyFromSeed(adminKeySeed)

            configFactory.withMutableUserConfigs {
                it.userGroups.set(group.copy(adminKey = adminKey.toBytes()))
            }

            // Update our promote state
            configFactory.withMutableGroupConfigs(
                groupId = groupId
            ) { configs ->
                configs.groupKeys.loadAdminKey(adminKey)

                configs.groupMembers.get(userAuth.accountId.hexString)?.let { member ->
                    member.setPromotionAccepted()
                    configs.groupMembers.set(member)
                }
            }

            // Remove lastHash so we can receive all the messages in the past
            lokiAPIDatabase.clearLastMessageHashes(groupId.hexString)
        }

        // Delete the promotion message remotely
        SnodeAPI.deleteMessage(
            userAuth.accountId.hexString,
            userAuth,
            listOf(promoteMessageHash)
        )
    }

    /**
     * Handle an invitation to a group.
     *
     * @param groupId the group ID
     * @param groupName the group name
     * @param authDataOrAdminSeed the auth data or admin key. If this is an invitation, this is the auth data, if this is a promotion, this is the admin key.
     * @param fromPromotion true if this is a promotion, false if this is an invitation
     * @param inviter the invite message sender
     * @return The newly created group info if the invitation is processed, null otherwise.
     */
    private suspend fun handleInvitation(
        groupId: AccountId,
        groupName: String,
        authDataOrAdminSeed: ByteArray,
        fromPromotion: Boolean,
        inviter: AccountId,
        inviterName: String?,
        inviteMessageTimestamp: Long,
        inviteMessageHash: String,
    ) {
        val recipient =
            Recipient.from(application, Address.fromSerialized(groupId.hexString), false)

        val shouldAutoApprove =
            storage.getRecipientApproved(Address.fromSerialized(inviter.hexString))
        val closedGroupInfo = GroupInfo.ClosedGroupInfo(
            groupAccountId = groupId.hexString,
            adminKey = authDataOrAdminSeed.takeIf { fromPromotion }?.let { GroupInfo.ClosedGroupInfo.adminKeyFromSeed(it) }?.toBytes(),
            authData = authDataOrAdminSeed.takeIf { !fromPromotion }?.toBytes(),
            priority = PRIORITY_VISIBLE,
            invited = !shouldAutoApprove,
            name = groupName,
            destroyed = false,
            joinedAtSecs = 0L,
            kicked = false,
        )

        configFactory.withMutableUserConfigs {
            it.userGroups.set(closedGroupInfo)
        }

        profileManager.setName(application, recipient, groupName)
        val groupThreadId = storage.getOrCreateThreadIdFor(recipient.address)
        storage.setRecipientApprovedMe(recipient, true)
        storage.setRecipientApproved(recipient, shouldAutoApprove)

        if (shouldAutoApprove) {
            approveGroupInvite(closedGroupInfo, inviteMessageHash)
        } else {
            lokiDatabase.addGroupInviteReferrer(groupThreadId, inviter.hexString, inviteMessageHash)
            // Clear existing message in the thread whenever we receive an invitation that we can't
            // auto approve
            storage.clearMessages(groupThreadId)

            // In most cases, when we receive invitation, the thread has just been created,
            // and "has_sent" is set to false. But there are cases that we could be "re-invited"
            // to a group, where we need to go through approval process again.
            // For a conversation to be in a unapproved list, "has_sent" must be false, hence
            // setting here explicitly. Details to be seen on ticket SES-2967.
            threadDatabase.setHasSent(groupThreadId, false)
            storage.insertGroupInviteControlMessage(
                sentTimestamp = inviteMessageTimestamp,
                senderPublicKey = inviter.hexString,
                senderName = inviterName,
                closedGroup = groupId,
                groupName = groupName
            )
        }
    }

    override suspend fun handleInviteResponse(
        groupId: AccountId,
        sender: AccountId,
        approved: Boolean
    ): Unit = scope.launchAndWait(groupId, "Handle invite response") {
        if (!approved) {
            // We should only see approved coming through
            return@launchAndWait
        }

        val adminKey = configFactory.getGroup(groupId)?.adminKey
        if (adminKey == null || adminKey.data.isEmpty()) {
            return@launchAndWait // We don't have the admin key, we can't process the invite response
        }

        configFactory.withMutableGroupConfigs(groupId) { configs ->
            val member = configs.groupMembers.get(sender.hexString)
            if (member != null) {
                configs.groupMembers.set(member.apply {
                    setInviteAccepted()
                })
            } else {
                Log.e(TAG, "User wasn't in the group membership to add!")
            }
        }
    }

    override suspend fun handleKicked(groupId: AccountId): Unit = scope.launchAndWait(groupId, "Handle kicked") {
        Log.d(TAG, "We were kicked from the group, delete and stop polling")

        val userId = requireNotNull(storage.getUserPublicKey()) { "No current user available" }
        val group = configFactory.getGroup(groupId) ?: return@launchAndWait

        // Retrieve the group name one last time from the group info,
        // as we are going to clear the keys, we won't have the chance to
        // read the group name anymore.
        val groupName = configFactory.withGroupConfigs(groupId) { configs ->
            configs.groupInfo.getName()
        } ?: group.name

        configFactory.withMutableUserConfigs {
            it.userGroups.set(
                group.copy(
                    kicked = true,
                    name = groupName
                )
            )
        }

        // Clear all messages in the group
        val threadId = storage.getThreadId(Address.fromSerialized(groupId.hexString))
        if (threadId != null) {
            storage.clearMessages(threadId)
        }

        // Clear all polling states
        lokiAPIDatabase.clearLastMessageHashes(groupId.hexString)
        lokiAPIDatabase.clearReceivedMessageHashValues(groupId.hexString)
        SessionMetaProtocol.clearReceivedMessages()

        configFactory.deleteGroupConfigs(groupId)
    }

    override suspend fun setName(groupId: AccountId, newName: String): Unit =
        scope.launchAndWait(groupId, "Set group name") {
            val adminKey = requireAdminAccess(groupId)

            val nameChanged = configFactory.withMutableGroupConfigs(groupId) { configs ->
                if (configs.groupInfo.getName() != newName) {
                    configs.groupInfo.setName(newName)
                    true
                } else {
                    false
                }
            }

            if (!nameChanged) {
                return@launchAndWait
            }

            val timestamp = clock.currentTimeMills()
            val signature = SodiumUtilities.sign(
                buildInfoChangeSignature(GroupUpdateInfoChangeMessage.Type.NAME, timestamp),
                adminKey
            )

            val message = GroupUpdated(
                GroupUpdateMessage.newBuilder()
                    .setInfoChangeMessage(
                        GroupUpdateInfoChangeMessage.newBuilder()
                            .setUpdatedName(newName)
                            .setType(GroupUpdateInfoChangeMessage.Type.NAME)
                            .setAdminSignature(ByteString.copyFrom(signature))
                    )
                    .build()
            ).apply {
                sentTimestamp = timestamp
            }

            storage.insertGroupInfoChange(message, groupId)
            MessageSender.sendAndAwait(message, Address.fromSerialized(groupId.hexString))
        }

    override suspend fun setDescription(groupId: AccountId, newDescription: String): Unit =
        scope.launchAndWait(groupId, "Set group description") {
            requireAdminAccess(groupId)

            configFactory.withMutableGroupConfigs(groupId) { configs ->
                if (configs.groupInfo.getDescription() != newDescription) {
                    configs.groupInfo.setDescription(newDescription)
                }
            }
        }

    override suspend fun requestMessageDeletion(
        groupId: AccountId,
        messageHashes: Set<String>
    ): Unit = scope.launchAndWait(groupId, "Request message deletion") {
        // To delete messages from a group, there are a few considerations:
        // 1. Messages are stored on every member's device, we need a way to ask them to delete their stored messages
        // 2. Messages are also stored on the group swarm, only the group admin can delete them
        // So we will send a group message to ask members to delete the messages,
        // meanwhile, if we are admin we can just delete those messages from the group swarm, and otherwise
        // the admins can pick up the group message and delete the messages on our behalf.

        val group = requireNotNull(configFactory.getGroup(groupId)) {
            "Group doesn't exist"
        }
        val userPubKey = requireNotNull(storage.getUserPublicKey()) { "No current user available" }

        // Check if we can actually delete these messages
        check(
            group.hasAdminKey() ||
                    storage.ensureMessageHashesAreSender(
                        messageHashes,
                        userPubKey,
                        groupId.hexString
                    )
        ) {
            "Cannot delete messages that are not sent by us"
        }

        // If we are admin, we can delete the messages from the group swarm
        group.adminKey?.data?.let { adminKey ->
            SnodeAPI.deleteMessage(
                publicKey = groupId.hexString,
                swarmAuth = OwnedSwarmAuth.ofClosedGroup(groupId, adminKey),
                serverHashes = messageHashes.toList()
            )
        }

        // Construct a message to ask members to delete the messages, sign if we are admin, then send
        val timestamp = clock.currentTimeMills()
        val signature = group.adminKey?.data?.let { key ->
            SodiumUtilities.sign(
                buildDeleteMemberContentSignature(
                    memberIds = emptyList(),
                    messageHashes,
                    timestamp
                ),
                key
            )
        }
        val message = GroupUpdated(
            GroupUpdateMessage.newBuilder()
                .setDeleteMemberContent(
                    GroupUpdateDeleteMemberContentMessage.newBuilder()
                        .addAllMessageHashes(messageHashes)
                        .let {
                            if (signature != null) it.setAdminSignature(
                                ByteString.copyFrom(
                                    signature
                                )
                            )
                            else it
                        }
                )
                .build()
        ).apply {
            sentTimestamp = timestamp
        }

        MessageSender.sendAndAwait(message, Address.fromSerialized(groupId.hexString))
    }

    override suspend fun handleDeleteMemberContent(
        groupId: AccountId,
        deleteMemberContent: GroupUpdateDeleteMemberContentMessage,
        timestamp: Long,
        sender: AccountId,
        senderIsVerifiedAdmin: Boolean,
    ): Unit = scope.launchAndWait(groupId, "Handle delete member content") {
        val threadId =
            requireNotNull(storage.getThreadId(Address.fromSerialized(groupId.hexString))) {
                "No thread ID found for the group"
            }

        val hashes = deleteMemberContent.messageHashesList
        val memberIds = deleteMemberContent.memberSessionIdsList

        if (hashes.isNotEmpty()) {
            // If the sender is a verified admin, or the sender is the actual sender of the messages,
            // we can mark them as deleted locally.
            if (senderIsVerifiedAdmin ||
                storage.ensureMessageHashesAreSender(
                    hashes.toSet(),
                    sender.hexString,
                    groupId.hexString
                )) {
                // We'll delete everything the admin says
                messageDataProvider.markMessagesAsDeleted(
                    threadId = threadId,
                    serverHashes = hashes,
                    displayedMessage = application.getString(
                        R.string.deleteMessageDeletedGlobally
                    )
                )
            }
        }

        // To be able to delete a user's messages, the sender must be a verified admin
        if (memberIds.isNotEmpty() && senderIsVerifiedAdmin) {
            for (member in memberIds) {
                messageDataProvider.markUserMessagesAsDeleted(threadId, timestamp, member, application.getString(
                    R.string.deleteMessageDeletedGlobally
                ))
            }
        }

        // Delete from swarm if we are admin
        val adminKey = configFactory.getGroup(groupId)?.adminKey?.data
        if (adminKey != null) {

            // If hashes are given, these are the messages to delete. To be able to delete these
            // messages from the swarm, the deletion request must be sent by an admin, or the messages
            // belong to the requester.
            if (hashes.isNotEmpty() && (
                        senderIsVerifiedAdmin || storage.ensureMessageHashesAreSender(
                            hashes = hashes.toSet(),
                            sender = sender.hexString,
                            closedGroupId = groupId.hexString))
            ) {
                SnodeAPI.deleteMessage(
                    groupId.hexString,
                    OwnedSwarmAuth.ofClosedGroup(groupId, adminKey),
                    hashes
                )
            }

            // If memberIds are given, all messages belong to these members will be deleted on the
            // swarm. These requests must be sent by an admin.
            if (memberIds.isNotEmpty() && senderIsVerifiedAdmin) {
                val userMessageHashes = memberIds.flatMap { memberId ->
                    messageDataProvider.getUserMessageHashes(threadId, memberId)
                }

                if (userMessageHashes.isNotEmpty()) {
                    SnodeAPI.deleteMessage(
                        groupId.hexString,
                        OwnedSwarmAuth.ofClosedGroup(groupId, adminKey),
                        userMessageHashes
                    )
                }
            }
        }
    }

    override fun handleGroupInfoChange(message: GroupUpdated, groupId: AccountId) {
        if (message.inner.hasInfoChangeMessage() && message.inner.infoChangeMessage.hasUpdatedExpiration()) {
            // If we receive a disappearing message update, we need to remove the existing timer control message
            storage.deleteGroupInfoMessages(
                groupId,
                UpdateMessageData.Kind.GroupExpirationUpdated::class.java
            )
        }

        storage.insertGroupInfoChange(message, groupId)
    }

    override fun onBlocked(groupAccountId: AccountId) {
        scope.launch(groupAccountId, "On blocked") {
            respondToInvitation(groupAccountId, false)

            // Remove this group from config regardless
            configFactory.removeGroup(groupAccountId)
        }
    }

    override fun setExpirationTimer(
        groupId: AccountId,
        mode: ExpiryMode,
        expiryChangeTimestampMs: Long
    ) {
        val adminKey = requireAdminAccess(groupId)

        // Construct a message to notify the group members about the expiration timer change
        val timestamp = clock.currentTimeMills()
        val signature = SodiumUtilities.sign(
            buildInfoChangeSignature(GroupUpdateInfoChangeMessage.Type.DISAPPEARING_MESSAGES, timestamp),
            adminKey
        )

        val message = GroupUpdated(
            GroupUpdateMessage.newBuilder()
                .setInfoChangeMessage(
                    GroupUpdateInfoChangeMessage.newBuilder()
                        .setType(GroupUpdateInfoChangeMessage.Type.DISAPPEARING_MESSAGES)
                        .setUpdatedExpiration(mode.expirySeconds.toInt())
                        .setAdminSignature(ByteString.copyFrom(signature))

                )
                .build()
        ).apply {
            sentTimestamp = timestamp
        }

        MessageSender.send(message, Address.fromSerialized(groupId.hexString))

        storage.deleteGroupInfoMessages(groupId, UpdateMessageData.Kind.GroupExpirationUpdated::class.java)
        storage.insertGroupInfoChange(message, groupId)
    }

    private fun BatchResponse.requireAllRequestsSuccessful(errorMessage: String) {
        val firstError = this.results.firstOrNull { it.code != 200 }
        require(firstError == null) { "$errorMessage: ${firstError!!.body}" }
    }

    private val Profile.profilePicture: UserPic?
        get() {
            val url = this.profilePictureURL
            val key = this.profileKey
            return if (url != null && key != null) {
                UserPic(url, key)
            } else {
                null
            }
        }
}
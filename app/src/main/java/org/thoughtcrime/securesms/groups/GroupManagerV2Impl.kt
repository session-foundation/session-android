package org.thoughtcrime.securesms.groups

import android.content.Context
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.GroupInfoConfig
import network.loki.messenger.libsession_util.GroupKeysConfig
import network.loki.messenger.libsession_util.GroupMembersConfig
import network.loki.messenger.libsession_util.UserGroupsConfig
import network.loki.messenger.libsession_util.util.Conversation
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.GroupMember
import network.loki.messenger.libsession_util.util.INVITE_STATUS_SENT
import network.loki.messenger.libsession_util.util.Sodium
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.jobs.ConfigurationSyncJob.Companion.messageInformation
import org.session.libsession.messaging.jobs.InviteContactsJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.visible.Profile
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.MessageAuthentication.buildDeleteMemberContentSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildInfoChangeVerifier
import org.session.libsession.messaging.utilities.MessageAuthentication.buildMemberChangeSignature
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.model.BatchResponse
import org.session.libsession.snode.model.StoreMessageResponse
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.withGroupConfigsOrNull
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.protos.SignalServiceProtos.DataMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateDeleteMemberContentMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateInfoChangeMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateInviteResponseMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMemberChangeMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.PollerFactory
import org.thoughtcrime.securesms.util.ConfigurationMessageUtilities
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GroupManagerV2Impl"

@Singleton
class GroupManagerV2Impl @Inject constructor(
    private val storage: StorageProtocol,
    private val configFactory: ConfigFactory,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val lokiDatabase: LokiMessageDatabase,
    private val pollerFactory: PollerFactory,
    private val profileManager: SSKEnvironment.ProfileManagerProtocol,
    @ApplicationContext val application: Context,
) : GroupManagerV2 {
    private val dispatcher = Dispatchers.Default

    /**
     * Require admin access to a group, and return the admin key.
     *
     * @throws IllegalArgumentException if the group does not exist or no admin key is found.
     */
    private fun requireAdminAccess(group: AccountId): ByteArray {
        return checkNotNull(configFactory
            .withUserConfigs { it.userGroups.getClosedGroup(group.hexString) }
            ?.adminKey
            ?.takeIf { it.isNotEmpty() }) { "Only admin is allowed to invite members" }
    }

    override suspend fun createGroup(
        groupName: String,
        groupDescription: String,
        members: Set<Contact>
    ): Recipient = withContext(dispatcher) {
        val ourAccountId =
            requireNotNull(storage.getUserPublicKey()) { "Our account ID is not available" }
        val ourKeys =
            requireNotNull(storage.getUserED25519KeyPair()) { "Our ED25519 key pair is not available" }
        val ourProfile = storage.getUserProfile()

        val groupCreationTimestamp = SnodeAPI.nowWithOffset

        // Create a group in the user groups config
        val group = configFactory.withMutableUserConfigs { configs ->
            configs.userGroups.createGroup().also(configs.userGroups::set)
        }

        val adminKey = checkNotNull(group.adminKey) { "Admin key is null for new group creation." }
        val groupId = group.groupAccountId
        val groupAuth = OwnedSwarmAuth.ofClosedGroup(groupId, adminKey)

        try {
            configFactory.withMutableGroupConfigs(groupId) { configs ->
                // Update group's information
                configs.groupInfo.setName(groupName)
                configs.groupInfo.setDescription(groupDescription)

                // Add members
                for (member in members) {
                    configs.groupMembers.set(
                        GroupMember(
                            sessionId = member.accountID,
                            name = member.name,
                            profilePicture = member.profilePicture ?: UserPic.DEFAULT,
                            inviteStatus = INVITE_STATUS_SENT
                        )
                    )
                }

                // Add ourselves as admin
                configs.groupMembers.set(
                    GroupMember(
                        sessionId = ourAccountId,
                        name = ourProfile.displayName,
                        profilePicture = ourProfile.profilePicture ?: UserPic.DEFAULT,
                        admin = true
                    )
                )

                // Manually re-key to prevent issue with linked admin devices
                configs.rekeys()
            }

            configFactory.withMutableUserConfigs {
                it.convoInfoVolatile.set(
                    Conversation.ClosedGroup(
                        groupId.hexString,
                        groupCreationTimestamp,
                        false
                    )
                )
            }

            val recipient =
                Recipient.from(application, Address.fromSerialized(groupId.hexString), false)

            // Apply various data locally
            profileManager.setName(application, recipient, groupName)
            storage.setRecipientApprovedMe(recipient, true)
            storage.setRecipientApproved(recipient, true)
            pollerFactory.updatePollers()

            // Invite members
            JobQueue.shared.add(
                InviteContactsJob(
                    groupSessionId = groupId.hexString,
                    memberSessionIds = members.map { it.accountID }.toTypedArray()
                )
            )

            recipient
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create group", e)

            // Remove the group from the user groups config is sufficient as a "rollback"
            configFactory.withMutableUserConfigs {
                it.userGroups.eraseClosedGroup(groupId.hexString)
            }
            throw e
        }
    }


    override suspend fun inviteMembers(
        group: AccountId,
        newMembers: List<AccountId>,
        shareHistory: Boolean
    ): Unit = withContext(dispatcher) {
        val adminKey = requireAdminAccess(group)
        val groupAuth = OwnedSwarmAuth.ofClosedGroup(group, adminKey)

        configFactory.withGroupConfigsOrNull(group) { infoConfig, membersConfig, keysConfig ->
            // Construct the new members in the config
            for (newMember in newMembers) {
                val toSet = membersConfig.get(newMember.hexString)
                    ?.let { existing ->
                        if (existing.inviteFailed || existing.invitePending) {
                            existing.copy(
                                inviteStatus = INVITE_STATUS_SENT,
                                supplement = shareHistory
                            )
                        } else {
                            existing
                        }
                    }
                    ?: membersConfig.getOrConstruct(newMember.hexString).let {
                        val contact = storage.getContactWithAccountID(newMember.hexString)
                        it.copy(
                            name = contact?.name,
                            profilePicture = contact?.profilePicture ?: UserPic.DEFAULT,
                            inviteStatus = INVITE_STATUS_SENT,
                            supplement = shareHistory
                        )
                    }

                membersConfig.set(toSet)
            }

            // Persist the member change to the db now for the UI to reflect the status change
            val timestamp = SnodeAPI.nowWithOffset
            configFactory.persistGroupConfigDump(membersConfig, group, timestamp)

            val batchRequests = mutableListOf<SnodeAPI.SnodeBatchRequestInfo>()
            val messagesToDelete = mutableListOf<String>() // List of message hashes

            // Depends on whether we want to share history, we may need to rekey or just adding supplement keys
            if (shareHistory) {
                for (member in newMembers) {
                    val memberKey = keysConfig.supplementFor(member.hexString)
                    batchRequests.add(
                        SnodeAPI.buildAuthenticatedStoreBatchInfo(
                            namespace = keysConfig.namespace(),
                            message = SnodeMessage(
                                recipient = group.hexString,
                                data = Base64.encodeBytes(memberKey),
                                ttl = SnodeMessage.CONFIG_TTL,
                                timestamp = timestamp
                            ),
                            auth = groupAuth,
                        )
                    )
                }
            } else {
                keysConfig.rekey(infoConfig, membersConfig)
            }

            // Call un-revocate API on new members, in case they have been removed before
            batchRequests += SnodeAPI.buildAuthenticatedUnrevokeSubKeyBatchRequest(
                groupAdminAuth = groupAuth,
                subAccountTokens = newMembers.map(keysConfig::getSubAccountToken)
            )

            keysConfig.messageInformation(groupAuth)?.let {
                batchRequests += it.batch
            }
            batchRequests += infoConfig.messageInformation(messagesToDelete, groupAuth).batch
            batchRequests += membersConfig.messageInformation(messagesToDelete, groupAuth).batch

            if (messagesToDelete.isNotEmpty()) {
                batchRequests += SnodeAPI.buildAuthenticatedDeleteBatchInfo(
                    auth = groupAuth,
                    messageHashes = messagesToDelete
                )
            }

            // Call the API
            val swarmNode = SnodeAPI.getSingleTargetSnode(group.hexString).await()
            val response = SnodeAPI.getBatchResponse(swarmNode, group.hexString, batchRequests)

            // Make sure every request is successful
            response.requireAllRequestsSuccessful("Failed to invite members")

            // Persist the keys config
            configFactory.saveGroupConfigs(keysConfig, infoConfig, membersConfig)

            // Send the invitation message to the new members
            JobQueue.shared.add(
                InviteContactsJob(
                    group.hexString,
                    newMembers.map { it.hexString }.toTypedArray()
                )
            )

            // Send a member change message to the group
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
            MessageSender.send(updatedMessage, Address.fromSerialized(group.hexString))
            storage.insertGroupInfoChange(updatedMessage, group)

            group
        }
    }


    override suspend fun removeMembers(
        groupAccountId: AccountId,
        removedMembers: List<AccountId>,
        removeMessages: Boolean
    ) {
        doRemoveMembers(
            group = groupAccountId,
            removedMembers = removedMembers,
            sendRemovedMessage = true,
            removeMemberMessages = removeMessages
        )
    }

    override suspend fun handleMemberLeft(message: GroupUpdated, closedGroupId: AccountId) {
        val userGroups = configFactory.userGroups ?: return
        val closedGroupHexString = closedGroupId.hexString
        val closedGroup = userGroups.getClosedGroup(closedGroupId.hexString) ?: return
        if (closedGroup.hasAdminKey()) {
            // re-key and do a new config removing the previous member
            doRemoveMembers(
                closedGroupId,
                listOf(AccountId(message.sender!!)),
                sendRemovedMessage = false,
                removeMemberMessages = false
            )
        } else {
            configFactory.getGroupMemberConfig(closedGroupId)?.use { memberConfig ->
                // if the leaving member is an admin, disable the group and remove it
                // This is just to emulate the "existing" group behaviour, this will need to be removed in future
                if (memberConfig.get(message.sender!!)?.admin == true) {
                    pollerFactory.pollerFor(closedGroupId)?.stop()
                    storage.getThreadId(Address.fromSerialized(closedGroupHexString))
                        ?.let(storage::deleteConversation)
                    configFactory.removeGroup(closedGroupId)
                }
            }
        }
    }

    override suspend fun leaveGroup(group: AccountId, deleteOnLeave: Boolean) {
        val canSendGroupMessage =
            configFactory.userGroups?.getClosedGroup(group.hexString)?.kicked != true
        val address = Address.fromSerialized(group.hexString)

        if (canSendGroupMessage) {
            MessageSender.sendNonDurably(
                message = GroupUpdated(
                    GroupUpdateMessage.newBuilder()
                        .setMemberLeftMessage(DataMessage.GroupUpdateMemberLeftMessage.getDefaultInstance())
                        .build()
                ),
                address = address,
                isSyncMessage = false
            ).await()

            MessageSender.sendNonDurably(
                message = GroupUpdated(
                    GroupUpdateMessage.newBuilder()
                        .setMemberLeftNotificationMessage(DataMessage.GroupUpdateMemberLeftNotificationMessage.getDefaultInstance())
                        .build()
                ),
                address = address,
                isSyncMessage = false
            ).await()
        }

        pollerFactory.pollerFor(group)?.stop()
        // TODO: set "deleted" and post to -10 group namespace?
        if (deleteOnLeave) {
            storage.getThreadId(address)?.let(storage::deleteConversation)
            configFactory.removeGroup(group)
            ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(application)
        }
    }

    override suspend fun promoteMember(group: AccountId, members: List<AccountId>): Unit = withContext(dispatcher) {
        val adminKey = requireAdminAccess(group)

        configFactory.withGroupConfigsOrNull(group) { info, membersConfig, keys ->
            // Promote the members by sending a message containing the admin key to each member's swarm,
            // we do this concurrently and then update the group configs after all the messages are sent.
            val promoteResult = members.asSequence()
                .mapNotNull { membersConfig.get(it.hexString) }
                .map { memberConfig ->
                    async {
                        val message = GroupUpdated(
                            GroupUpdateMessage.newBuilder()
                                .setPromoteMessage(
                                    DataMessage.GroupUpdatePromoteMessage.newBuilder()
                                        .setGroupIdentitySeed(ByteString.copyFrom(adminKey))
                                        .setName(info.getName())
                                )
                                .build()
                        )

                        try {
                            MessageSender.sendNonDurably(
                                message = message,
                                address = Address.fromSerialized(memberConfig.sessionId),
                                isSyncMessage = false
                            ).await()

                            memberConfig.setPromoteSent()
                        } catch (ec: Exception) {
                            Log.e(TAG, "Failed to send promote message", ec)
                            memberConfig.setPromoteFailed()
                        }
                    }
                }
                .toList()

            for (result in promoteResult) {
                membersConfig.set(result.await())
            }

            configFactory.saveGroupConfigs(keys, info, membersConfig)
        }

        // Send a group update message to the group telling members someone has been promoted
        val groupDestination = Destination.ClosedGroup(group.hexString)
        ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(groupDestination)
        val timestamp = SnodeAPI.nowWithOffset
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

        MessageSender.send(message, Address.fromSerialized(group.hexString))
        storage.insertGroupInfoChange(message, group)
    }

    private suspend fun doRemoveMembers(
        group: AccountId,
        removedMembers: List<AccountId>,
        sendRemovedMessage: Boolean,
        removeMemberMessages: Boolean
    ) = withContext(dispatcher) {
        val adminKey = requireAdminAccess(group)
        val groupAuth = OwnedSwarmAuth.ofClosedGroup(group, adminKey)

        configFactory.withGroupConfigsOrNull(group) { info, members, keys ->
            // To remove a member from a group, we need to first:
            // 1. Notify the swarm that this member's key has bene revoked
            // 2. Send a "kicked" message to a special namespace that the kicked member can still read
            // 3. Optionally, send "delete member messages" to the group. (So that every device in the group
            //    delete this member's messages locally.)
            // These three steps will be included in a sequential call as they all need to be done in order.
            // After these steps are all done, we will do the following:
            // Update the group configs to remove the member, sync if needed, then
            // delete the member's messages locally and remotely.
            val messageSendTimestamp = SnodeAPI.nowWithOffset

            val essentialRequests = buildList {
                this += SnodeAPI.buildAuthenticatedRevokeSubKeyBatchRequest(
                    groupAdminAuth = groupAuth,
                    subAccountTokens = removedMembers.map(keys::getSubAccountToken)
                )

                this += Sodium.encryptForMultipleSimple(
                    messages = removedMembers.map { "${it.hexString}-${keys.currentGeneration()}".encodeToByteArray() }
                        .toTypedArray(),
                    recipients = removedMembers.map { it.pubKeyBytes }.toTypedArray(),
                    ed25519SecretKey = adminKey,
                    domain = Sodium.KICKED_DOMAIN
                ).let { encryptedForMembers ->
                    SnodeAPI.buildAuthenticatedStoreBatchInfo(
                        namespace = Namespace.REVOKED_GROUP_MESSAGES(),
                        message = SnodeMessage(
                            recipient = group.hexString,
                            data = Base64.encodeBytes(encryptedForMembers),
                            ttl = SnodeMessage.CONFIG_TTL,
                            timestamp = messageSendTimestamp
                        ),
                        auth = groupAuth
                    )
                }

                if (removeMemberMessages) {
                    val adminSignature =
                        SodiumUtilities.sign(
                            buildDeleteMemberContentSignature(
                                memberIds = removedMembers,
                                messageHashes = emptyList(),
                                timestamp = messageSendTimestamp
                            ), adminKey
                        )

                    this += SnodeAPI.buildAuthenticatedStoreBatchInfo(
                        namespace = Namespace.CLOSED_GROUP_MESSAGES(),
                        message = MessageSender.buildWrappedMessageToSnode(
                            destination = Destination.ClosedGroup(group.hexString),
                            message = GroupUpdated(
                                GroupUpdateMessage.newBuilder()
                                    .setDeleteMemberContent(
                                        GroupUpdateDeleteMemberContentMessage.newBuilder()
                                            .addAllMemberSessionIds(removedMembers.map { it.hexString })
                                            .setAdminSignature(ByteString.copyFrom(adminSignature))
                                    )
                                    .build()
                            ).apply { sentTimestamp = messageSendTimestamp },
                            isSyncMessage = false
                        ),
                        auth = groupAuth
                    )
                }
            }

            val snode = SnodeAPI.getSingleTargetSnode(group.hexString).await()
            val responses = SnodeAPI.getBatchResponse(
                snode,
                group.hexString,
                essentialRequests,
                sequence = true
            )

            responses.requireAllRequestsSuccessful("Failed to execute essential steps for removing member")

            // Next step: update group configs, rekey, remove member messages if required
            val messagesToDelete = mutableListOf<String>()
            for (member in removedMembers) {
                members.erase(member.hexString)
            }

            keys.rekey(info, members)

            if (removeMemberMessages) {
                val threadId = storage.getThreadId(Address.fromSerialized(group.hexString))
                if (threadId != null) {
                    for (member in removedMembers) {
                        for (msg in mmsSmsDatabase.getUserMessages(threadId, member.hexString)) {
                            val serverHash = lokiDatabase.getMessageServerHash(msg.id, msg.isMms)
                            if (serverHash != null) {
                                messagesToDelete.add(serverHash)
                            }
                        }

                        storage.deleteMessagesByUser(threadId, member.hexString)
                    }
                }
            }

            val requests = buildList {
                keys.messageInformation(groupAuth)?.let {
                    this += "Sync keys config messages" to it.batch
                }

                this += "Sync info config messages" to info.messageInformation(
                    messagesToDelete,
                    groupAuth
                ).batch

                this += "Sync member config messages" to members.messageInformation(
                    messagesToDelete,
                    groupAuth
                ).batch

                this += "Delete outdated config and member messages" to SnodeAPI.buildAuthenticatedDeleteBatchInfo(
                    groupAuth,
                    messagesToDelete
                )
            }

            val response = SnodeAPI.getBatchResponse(
                snode = snode,
                publicKey = group.hexString,
                requests = requests.map { it.second }
            )

            response.requireAllRequestsSuccessful("Failed to remove members")

            // Persist the changes
            configFactory.saveGroupConfigs(keys, info, members)

            if (sendRemovedMessage) {
                val timestamp = messageSendTimestamp
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
                MessageSender.send(message, Destination.ClosedGroup(group.hexString), false)
                storage.insertGroupInfoChange(message, group)
            }
        }

        ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(
            Destination.ClosedGroup(group.hexString)
        )
    }

    override suspend fun respondToInvitation(groupId: AccountId, approved: Boolean) = withContext(dispatcher) {
        val groups = requireNotNull(configFactory.userGroups) {
            "User groups config is not available"
        }

        val threadId = checkNotNull(storage.getThreadId(Address.fromSerialized(groupId.hexString))) {
            "No thread has been created for the group"
        }

        val group = requireNotNull(groups.getClosedGroup(groupId.hexString)) {
            "Group must have been created into the config object before responding to an invitation"
        }

        // Whether approved or not, delete the invite
        lokiDatabase.deleteGroupInviteReferrer(threadId)

        if (approved) {
            approveGroupInvite(groups, group, threadId)
        } else {
            groups.eraseClosedGroup(groupId.hexString)
            storage.deleteConversation(threadId)
        }
    }

    private fun approveGroupInvite(
        groups: UserGroupsConfig,
        group: GroupInfo.ClosedGroupInfo,
        threadId: Long,
    ) {
        val key = requireNotNull(storage.getUserPublicKey()) {
            "Our account ID is not available"
        }

        // Clear the invited flag of the group in the config
        groups.set(group.copy(invited = false))
        configFactory.persist(forConfigObject = groups, timestamp = SnodeAPI.nowWithOffset)
        ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(application)

        if (group.adminKey == null) {
            // Send an invite response to the group if we are invited as a regular member
            val inviteResponse = GroupUpdateInviteResponseMessage.newBuilder()
                .setIsApproved(true)
            val responseData = GroupUpdateMessage.newBuilder()
                .setInviteResponse(inviteResponse)
            val responseMessage = GroupUpdated(responseData.build())
            storage.clearMessages(threadId)
            // this will fail the first couple of times :)
            MessageSender.send(responseMessage, Address.fromSerialized(group.groupAccountId.hexString))
        } else {
            // If we are invited as admin, we can just update the group info ourselves
            configFactory.withGroupConfigsOrNull(group.groupAccountId) { info, members, keys ->
                members.get(key)?.let { member ->
                    members.set(member.setPromoteSuccess().setAccepted())

                    configFactory.saveGroupConfigs(keys, info, members)
                }

                Unit
            }

            ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(
                destination = Destination.ClosedGroup(group.groupAccountId.hexString)
            )
        }

        pollerFactory.pollerFor(group.groupAccountId)?.start()
    }

    override suspend fun onReceiveInvitation(
        groupId: AccountId,
        groupName: String,
        authData: ByteArray,
        inviter: AccountId,
        inviteMessageHash: String?
    ) = withContext(dispatcher) {
        handleInvitation(
            groupId = groupId,
            groupName = groupName,
            authDataOrAdminKey = authData,
            fromPromotion = false,
            inviter = inviter
        )

        // Delete the invite message remotely
        if (inviteMessageHash != null) {
            val auth = requireNotNull(storage.userAuth) { "No current user available" }
            SnodeAPI.sendBatchRequest(
                auth.accountId,
                SnodeAPI.buildAuthenticatedDeleteBatchInfo(auth, listOf(inviteMessageHash)),
            )
        }
    }

    override suspend fun onReceivePromotion(
        groupId: AccountId,
        groupName: String,
        adminKey: ByteArray,
        promoter: AccountId,
        promoteMessageHash: String?
    ) = withContext(dispatcher) {
        val groups = requireNotNull(configFactory.userGroups) {
            "User groups config is not available"
        }

        val userAuth = requireNotNull(storage.userAuth) { "No current user available" }
        var group = groups.getClosedGroup(groupId.hexString)

        if (group == null) {
            // If we haven't got the group in the config, it could mean that we haven't
            // processed the invitation, or the invitation message is lost. We'll need to
            // go through the invitation process again.
            handleInvitation(
                groupId = groupId,
                groupName = groupName,
                authDataOrAdminKey = adminKey,
                fromPromotion = true,
                inviter = promoter,
            )
        } else {
            // If we have the group in the config, we can just update the admin key
            group = group.copy(adminKey = adminKey)
            groups.set(group)
            configFactory.persist(groups, SnodeAPI.nowWithOffset)

            // Update our promote state
            configFactory.withGroupConfigsOrNull(groupId) { info, members, keys ->
                members.get(userAuth.accountId.hexString)?.let { member ->
                    members.set(member.setPromoteSuccess())

                    configFactory.saveGroupConfigs(keys, info, members)
                }

                Unit
            }

            ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(
                destination = Destination.ClosedGroup(groupId.hexString)
            )

            ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(application)
        }

        // Delete the promotion message remotely
        if (promoteMessageHash != null) {
            SnodeAPI.sendBatchRequest(
                userAuth.accountId,
                SnodeAPI.buildAuthenticatedDeleteBatchInfo(userAuth, listOf(promoteMessageHash)),
            )
        }
    }

    /**
     * Handle an invitation to a group.
     *
     * @param groupId the group ID
     * @param groupName the group name
     * @param authDataOrAdminKey the auth data or admin key. If this is an invitation, this is the auth data, if this is a promotion, this is the admin key.
     * @param fromPromotion true if this is a promotion, false if this is an invitation
     * @param inviter the invite message sender
     * @return The newly created group info if the invitation is processed, null otherwise.
     */
    private fun handleInvitation(
        groupId: AccountId,
        groupName: String,
        authDataOrAdminKey: ByteArray,
        fromPromotion: Boolean,
        inviter: AccountId,
    ) {
        val groups = requireNotNull(configFactory.userGroups) {
            "User groups config is not available"
        }

        // If we have already received an invitation in the past, we should not process this one
        if (groups.getClosedGroup(groupId.hexString)?.invited == true) {
            return
        }

        val recipient = Recipient.from(application, Address.fromSerialized(groupId.hexString), false)

        val shouldAutoApprove = storage.getRecipientApproved(Address.fromSerialized(inviter.hexString))
        val closedGroupInfo = GroupInfo.ClosedGroupInfo(
            groupAccountId = groupId,
            adminKey = authDataOrAdminKey.takeIf { fromPromotion },
            authData = authDataOrAdminKey.takeIf { !fromPromotion },
            priority = PRIORITY_VISIBLE,
            invited = !shouldAutoApprove,
            name = groupName,
        )
        groups.set(closedGroupInfo)

        configFactory.persist(groups, SnodeAPI.nowWithOffset)
        profileManager.setName(application, recipient, groupName)
        val groupThreadId = storage.getOrCreateThreadIdFor(recipient.address)
        storage.setRecipientApprovedMe(recipient, true)
        storage.setRecipientApproved(recipient, shouldAutoApprove)
        if (shouldAutoApprove) {
            approveGroupInvite(groups, closedGroupInfo, groupThreadId)
        } else {
            lokiDatabase.addGroupInviteReferrer(groupThreadId, inviter.hexString)
            storage.insertGroupInviteControlMessage(SnodeAPI.nowWithOffset, inviter.hexString, groupId, groupName)
        }
    }

    override suspend fun handleInviteResponse(
        groupId: AccountId,
        sender: AccountId,
        approved: Boolean
    ): Unit = withContext(dispatcher) {
        if (!approved) {
            // We should only see approved coming through
            return@withContext
        }

        val groups = requireNotNull(configFactory.userGroups) {
            "User groups config is not available"
        }

        val adminKey = groups.getClosedGroup(groupId.hexString)?.adminKey
        if (adminKey == null || adminKey.isEmpty()) {
            return@withContext // We don't have the admin key, we can't process the invite response
        }

        configFactory.withGroupConfigsOrNull(groupId) { info, members, keys ->
            val member = members.get(sender.hexString)
            if (member == null) {
                Log.e(TAG, "User wasn't in the group membership to add!")
                return@withContext
            }

            members.set(member.setAccepted())

            configFactory.saveGroupConfigs(keys, info, members)
            ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(
                Destination.ClosedGroup(groupId.hexString)
            )
        }
    }

    override suspend fun handleKicked(groupId: AccountId): Unit = withContext(dispatcher) {
        Log.d(TAG, "We were kicked from the group, delete and stop polling")

        // Stop polling the group immediately
        pollerFactory.pollerFor(groupId)?.stop()

        val userId = requireNotNull(storage.getUserPublicKey()) { "No current user available" }
        val userGroups = requireNotNull(configFactory.userGroups) { "User groups config is not available" }
        val group = userGroups.getClosedGroup(groupId.hexString) ?: return@withContext

        // Retrieve the group name one last time from the group info,
        // as we are going to clear the keys, we won't have the chance to
        // read the group name anymore.
        val groupName = configFactory.getGroupInfoConfig(groupId)
            ?.use { it.getName() }
            ?: group.name

        userGroups.set(group.copy(
            authData = null,
            adminKey = null,
            name = groupName
        ))

        configFactory.persist(userGroups, SnodeAPI.nowWithOffset)

        storage.insertIncomingInfoMessage(
            context = MessagingModuleConfiguration.shared.context,
            senderPublicKey = userId,
            groupID = groupId.hexString,
            type = SignalServiceGroup.Type.KICKED,
            name = groupName,
            members = emptyList(),
            admins = emptyList(),
            sentTimestamp = SnodeAPI.nowWithOffset,
        )
    }

    override suspend fun setName(groupId: AccountId, newName: String): Unit = withContext(dispatcher) {
        val adminKey = requireAdminAccess(groupId)

        configFactory.getGroupInfoConfig(groupId)?.use { infoConfig ->
            infoConfig.setName(newName)
            configFactory.persist(infoConfig, SnodeAPI.nowWithOffset, forPublicKey = groupId.hexString)
        }

        val groupDestination = Destination.ClosedGroup(groupId.hexString)
        ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(groupDestination)
        val timestamp = SnodeAPI.nowWithOffset
        val signature = SodiumUtilities.sign(
            buildInfoChangeVerifier(GroupUpdateInfoChangeMessage.Type.NAME, timestamp),
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

        MessageSender.sendNonDurably(message, Address.fromSerialized(groupId.hexString), false).await()
        storage.insertGroupInfoChange(message, groupId)
    }

    override suspend fun requestMessageDeletion(
        groupId: AccountId,
        messageHashes: List<String>
    ): Unit = withContext(dispatcher) {
        // To delete messages from a group, there are a few considerations:
        // 1. Messages are stored on every member's device, we need a way to ask them to delete their stored messages
        // 2. Messages are also stored on the group swarm, only the group admin can delete them
        // So we will send a group message to ask members to delete the messages,
        // meanwhile, if we are admin we can just delete those messages from the group swarm, and otherwise
        // the admins can pick up the group message and delete the messages on our behalf.

        val userGroups = requireNotNull(configFactory.userGroups) { "User groups config is not available" }
        val group = requireNotNull(userGroups.getClosedGroup(groupId.hexString)) {
            "Group doesn't exist"
        }
        val userPubKey = requireNotNull(storage.getUserPublicKey()) { "No current user available" }

        // Check if we can actually delete these messages
        check(
            group.hasAdminKey() ||
            storage.ensureMessageHashesAreSender(messageHashes.toSet(), userPubKey, groupId.hexString)
        ) {
            "Cannot delete messages that are not sent by us"
        }

        // If we are admin, we can delete the messages from the group swarm
        group.adminKey?.let { adminKey ->
            deleteMessageFromGroupSwarm(groupId, OwnedSwarmAuth.ofClosedGroup(groupId, adminKey), messageHashes)
        }

        // Construct a message to ask members to delete the messages, sign if we are admin, then send
        val timestamp = SnodeAPI.nowWithOffset
        val signature = group.adminKey?.let { key ->
            SodiumUtilities.sign(
                buildDeleteMemberContentSignature(memberIds = emptyList(), messageHashes, timestamp),
                key
            )
        }
        val message = GroupUpdated(
            GroupUpdateMessage.newBuilder()
                .setDeleteMemberContent(
                    GroupUpdateDeleteMemberContentMessage.newBuilder()
                        .addAllMessageHashes(messageHashes)
                        .let {
                            if (signature != null) it.setAdminSignature(ByteString.copyFrom(signature))
                            else it
                        }
                )
                .build()
        ).apply {
            sentTimestamp = timestamp
        }

        val groupAddress = Address.fromSerialized(groupId.hexString)
        MessageSender.sendNonDurably(message, groupAddress, false).await()
    }

    override suspend fun handleDeleteMemberContent(
        groupId: AccountId,
        deleteMemberContent: GroupUpdateDeleteMemberContentMessage,
        sender: AccountId,
        senderIsVerifiedAdmin: Boolean,
    ): Unit = withContext(dispatcher) {
        val threadId = requireNotNull(storage.getThreadId(Address.fromSerialized(groupId.hexString))) {
            "No thread ID found for the group"
        }

        val hashes = deleteMemberContent.messageHashesList
        val memberIds = deleteMemberContent.memberSessionIdsList

        if (hashes.isNotEmpty()) {
            if (senderIsVerifiedAdmin) {
                // We'll delete everything the admin says
                storage.deleteMessagesByHash(threadId, hashes)
            } else if (storage.ensureMessageHashesAreSender(hashes.toSet(), sender.hexString, groupId.hexString)) {
                // ensure that all message hashes belong to user
                // storage delete
                storage.deleteMessagesByHash(threadId, hashes)
            }
        }

        if (memberIds.isNotEmpty() && senderIsVerifiedAdmin) {
            for (member in memberIds) {
                storage.deleteMessagesByUser(threadId, member)
            }
        }

        val adminKey = configFactory.userGroups?.getClosedGroup(groupId.hexString)?.adminKey
        if (!senderIsVerifiedAdmin && adminKey != null) {
            // If the deletion request comes from a non-admin, and we as an admin, will also delete
            // the content from the swarm, provided that the messages are actually sent by that user
            if (storage.ensureMessageHashesAreSender(hashes.toSet(), sender.hexString, groupId.hexString)) {
                deleteMessageFromGroupSwarm(groupId, OwnedSwarmAuth.ofClosedGroup(groupId, adminKey), hashes)
            }

            // The non-admin user shouldn't be able to delete other user's messages so we will
            // ignore the memberIds in the message
        }
    }

    private suspend fun deleteMessageFromGroupSwarm(groupId: AccountId, auth: OwnedSwarmAuth, hashes: List<String>) {
        SnodeAPI.sendBatchRequest(
            groupId, SnodeAPI.buildAuthenticatedDeleteBatchInfo(auth, hashes)
        )
    }

    private fun BatchResponse.requireAllRequestsSuccessful(errorMessage: String) {
        val firstError = this.results.firstOrNull { it.code != 200 }
        require(firstError == null) { "$errorMessage: ${firstError!!.body}" }
    }

    private val Contact.profilePicture: UserPic?
        get() {
            val url = this.profilePictureURL
            val key = this.profilePictureEncryptionKey
            return if (url != null && key != null) {
                UserPic(url, key)
            } else {
                null
            }
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
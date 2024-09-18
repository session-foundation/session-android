package org.thoughtcrime.securesms.groups

import android.content.Context
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.libsession_util.util.INVITE_STATUS_SENT
import network.loki.messenger.libsession_util.util.Sodium
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.jobs.ConfigurationSyncJob.Companion.messageInformation
import org.session.libsession.messaging.jobs.InviteContactsJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.MessageAuthentication.buildDeleteMemberContentSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildMemberChangeSignature
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.model.BatchResponse
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.withGroupConfigsOrNull
import org.session.libsignal.protos.SignalServiceProtos.DataMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateDeleteMemberContentMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMemberChangeMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Namespace
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.PollerFactory
import org.thoughtcrime.securesms.util.ConfigurationMessageUtilities
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupManagerV2Impl @Inject constructor(
    val storage: StorageProtocol,
    val configFactory: ConfigFactory,
    val mmsSmsDatabase: MmsSmsDatabase,
    val lokiDatabase: LokiMessageDatabase,
    val pollerFactory: PollerFactory,
    @ApplicationContext val application: Context,
) : GroupManagerV2 {
    /**
     * Require admin access to a group, and return the admin key.
     *
     * @throws IllegalArgumentException if the group does not exist or no admin key is found.
     */
    private fun requireAdminAccess(group: AccountId): ByteArray {
        return checkNotNull(configFactory
            .userGroups
            ?.getClosedGroup(group.hexString)
            ?.adminKey
            ?.takeIf { it.isNotEmpty() }) { "Only admin is allowed to invite members" }
    }

    override suspend fun inviteMembers(
        group: AccountId,
        newMembers: List<AccountId>,
        shareHistory: Boolean
    ) {
        val adminKey = requireAdminAccess(group)
        val groupAuth = OwnedSwarmAuth.ofClosedGroup(group, adminKey)

        configFactory.withGroupConfigsOrNull(group) { infoConfig, membersConfig, keysConfig ->
            // Construct the new members in the config
            for (newMember in newMembers) {
                val toSet = membersConfig.get(newMember.hexString)
                    ?.let { existing ->
                        if (existing.inviteFailed || existing.invitePending) {
                            existing.copy(inviteStatus = INVITE_STATUS_SENT, supplement = shareHistory)
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
                    batchRequests.add(SnodeAPI.buildAuthenticatedStoreBatchInfo(
                        namespace = keysConfig.namespace(),
                        message = SnodeMessage(
                            recipient = group.hexString,
                            data = Base64.encodeBytes(memberKey),
                            ttl = SnodeMessage.CONFIG_TTL,
                            timestamp = timestamp
                        ),
                        auth = groupAuth,
                    ))
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
            JobQueue.shared.add(InviteContactsJob(group.hexString, newMembers.map { it.hexString }.toTypedArray()))

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
        val canSendGroupMessage = configFactory.userGroups?.getClosedGroup(group.hexString)?.kicked != true
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

    override suspend fun promoteMember(group: AccountId, members: List<AccountId>) {
        val adminKey = requireAdminAccess(group)

        configFactory.withGroupConfigsOrNull(group) { info, membersConfig, keys ->
            for (member in members) {
                val promoted = membersConfig.get(member.hexString)?.setPromoteSent() ?: continue
                membersConfig.set(promoted)

                val message = GroupUpdated(
                    GroupUpdateMessage.newBuilder()
                        .setPromoteMessage(
                            DataMessage.GroupUpdatePromoteMessage.newBuilder()
                                .setGroupIdentitySeed(ByteString.copyFrom(adminKey))
                                .setName(info.getName())
                        )
                        .build()
                )
                MessageSender.send(message, Address.fromSerialized(group.hexString))
            }

            configFactory.saveGroupConfigs(keys, info, membersConfig)
        }


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

        MessageSender.send(message, Address.fromSerialized(groupDestination.publicKey))
        storage.insertGroupInfoChange(message, group)
    }

    private suspend fun doRemoveMembers(group: AccountId,
                                        removedMembers: List<AccountId>,
                                        sendRemovedMessage: Boolean,
                                        removeMemberMessages: Boolean) {
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
                    messages = removedMembers.map{"${it.hexString}-${keys.currentGeneration()}".encodeToByteArray()}.toTypedArray(),
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
                        ), adminKey)

                    this += SnodeAPI.buildAuthenticatedStoreBatchInfo(
                        namespace = Namespace.CLOSED_GROUP_MESSAGES(),
                        message = MessageSender.buildWrappedMessageToSnode(
                            destination = Destination.ClosedGroup(group.hexString),
                            message = GroupUpdated(GroupUpdateMessage.newBuilder()
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
            val responses = SnodeAPI.getBatchResponse(snode, group.hexString, essentialRequests, sequence = true)

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

                this += "Sync info config messages" to info.messageInformation(messagesToDelete, groupAuth).batch
                this += "Sync member config messages" to members.messageInformation(messagesToDelete, groupAuth).batch
                this += "Delete outdated config and member messages" to SnodeAPI.buildAuthenticatedDeleteBatchInfo(groupAuth, messagesToDelete)
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
                    buildMemberChangeSignature(GroupUpdateMemberChangeMessage.Type.REMOVED, timestamp),
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

    private fun BatchResponse.requireAllRequestsSuccessful(errorMessage: String) {
        val firstError = this.results.firstOrNull { it.code != 200 }
        require(firstError == null) { "$errorMessage: ${firstError!!.body}" }
    }

    private val Contact.profilePicture: UserPic? get() {
        val url = this.profilePictureURL
        val key = this.profilePictureEncryptionKey
        return if (url != null && key != null) {
            UserPic(url, key)
        } else {
            null
        }
    }
}
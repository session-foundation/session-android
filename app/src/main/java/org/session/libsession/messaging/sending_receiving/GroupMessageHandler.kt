package org.session.libsession.messaging.sending_receiving

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.protocol.DecodedPro
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.messages.ProfileUpdateHandler
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.utilities.MessageAuthentication.buildDeleteMemberContentSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildGroupInviteSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildInfoChangeSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildMemberChangeSignature
import org.session.libsession.network.SnodeClock
import org.session.protos.SessionProtos
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import java.security.SignatureException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupMessageHandler @Inject constructor(
    private val profileUpdateHandler: ProfileUpdateHandler,
    private val storage: StorageProtocol,
    private val groupManagerV2: GroupManagerV2,
    @param:ManagerScope private val scope: CoroutineScope,
    private val clock: SnodeClock,
) {
    fun handleGroupUpdated(
        message: GroupUpdated,
        groupId: AccountId?,
        proto: SessionProtos.Content,
        pro: DecodedPro?
    ) {
        val inner = message.inner
        if (groupId == null &&
            !inner.hasInviteMessage() && !inner.hasPromoteMessage()) {
            throw NullPointerException("Message wasn't polled from a closed group!")
        }

        // Update profile if needed
        ProfileUpdateHandler.Updates.create(proto, clock.currentTimeMillis(), pro)?.let { updates ->
            profileUpdateHandler.handleProfileUpdate(
                senderId = AccountId(message.sender!!),
                updates = updates,
                fromCommunity = null // Groupv2 is not a community
            )
        }

        when {
            inner.hasInviteMessage() -> handleNewLibSessionClosedGroupMessage(message, proto)
            inner.hasInviteResponse() -> handleInviteResponse(message, groupId!!)
            inner.hasPromoteMessage() -> handlePromotionMessage(message, proto)
            inner.hasInfoChangeMessage() -> handleGroupInfoChange(message, groupId!!)
            inner.hasMemberChangeMessage() -> handleMemberChange(message, groupId!!)
            inner.hasMemberLeftMessage() -> handleMemberLeft(message, groupId!!)
            inner.hasMemberLeftNotificationMessage() -> handleMemberLeftNotification(message, groupId!!)
            inner.hasDeleteMemberContent() -> handleDeleteMemberContent(message, groupId!!)
        }
    }

    private fun handleNewLibSessionClosedGroupMessage(message: GroupUpdated, proto: SessionProtos.Content) {
        val storage = storage
        val ourUserId = storage.getUserPublicKey()!!
        val invite = message.inner.inviteMessage
        val groupId = AccountId(invite.groupSessionId)
        verifyAdminSignature(
            groupSessionId = groupId,
            signatureData = invite.adminSignature.toByteArray(),
            messageToValidate = buildGroupInviteSignature(AccountId(ourUserId), message.sentTimestamp!!)
        )

        val sender = message.sender!!
        val adminId = AccountId(sender)
        scope.launch {
            try {
                groupManagerV2
                    .handleInvitation(
                        groupId = groupId,
                        groupName = invite.name,
                        authData = invite.memberAuthData.toByteArray(),
                        inviter = adminId,
                        inviterName = if (proto.hasDataMessage() && proto.dataMessage.hasProfile() && proto.dataMessage.profile.hasDisplayName())
                            proto.dataMessage.profile.displayName
                        else null,
                        inviteMessageHash = message.serverHash!!,
                        inviteMessageTimestamp = message.sentTimestamp!!,
                    )
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle invite message", e)
            }
        }
    }

    /**
     * Does nothing on successful signature verification, throws otherwise.
     * Assumes the signer is using the ed25519 group key signing key
     * @param groupSessionId the AccountId of the group to check the signature against
     * @param signatureData the byte array supplied to us through a protobuf message from the admin
     * @param messageToValidate the expected values used for this signature generation, often something like `INVITE||{inviteeSessionId}||{timestamp}`
     * @throws SignatureException if signature cannot be verified with given parameters
     */
    private fun verifyAdminSignature(groupSessionId: AccountId, signatureData: ByteArray, messageToValidate: ByteArray) {
        val groupPubKey = groupSessionId.pubKeyBytes
        if (!ED25519.verify(signature = signatureData, ed25519PublicKey = groupPubKey, message = messageToValidate)) {
            throw SignatureException("Verification failed for signature data")
        }
    }

    private fun handleInviteResponse(message: GroupUpdated, closedGroup: AccountId) {
        val sender = message.sender!!
        // val profile = message // maybe we do need data to be the inner so we can access profile
        val approved = message.inner.inviteResponse.isApproved
        scope.launch {
            try {
                groupManagerV2.handleInviteResponse(closedGroup, AccountId(sender), approved)
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle invite response", e)
            }
        }
    }


    private fun handlePromotionMessage(message: GroupUpdated, proto: SessionProtos.Content) {
        val promotion = message.inner.promoteMessage
        val seed = promotion.groupIdentitySeed.toByteArray()
        val sender = message.sender!!
        val adminId = AccountId(sender)
        scope.launch {
            try {
                groupManagerV2
                    .handlePromotion(
                        groupId = AccountId(IdPrefix.GROUP, ED25519.generate(seed).pubKey.data),
                        groupName = promotion.name,
                        adminKeySeed = seed,
                        promoter = adminId,
                        promoterName = if (proto.hasDataMessage() && proto.dataMessage.hasProfile() && proto.dataMessage.profile.hasDisplayName())
                            proto.dataMessage.profile.displayName
                        else null,
                        promoteMessageHash = message.serverHash!!,
                        promoteMessageTimestamp = message.sentTimestamp!!,
                    )
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle promotion message", e)
            }
        }
    }

    private fun handleGroupInfoChange(message: GroupUpdated, closedGroup: AccountId) {
        val inner = message.inner
        val infoChanged = inner.infoChangeMessage ?: return
        if (!infoChanged.hasAdminSignature()) return Log.e("GroupUpdated", "Info changed message doesn't contain admin signature")
        val adminSignature = infoChanged.adminSignature
        val type = infoChanged.type
        val timestamp = message.sentTimestamp!!
        verifyAdminSignature(closedGroup, adminSignature.toByteArray(), buildInfoChangeSignature(type, timestamp))

        groupManagerV2.handleGroupInfoChange(message, closedGroup)
    }


    private fun handleMemberChange(message: GroupUpdated, closedGroup: AccountId) {
        val memberChange = message.inner.memberChangeMessage
        val type = memberChange.type
        val timestamp = message.sentTimestamp!!
        verifyAdminSignature(closedGroup,
            memberChange.adminSignature.toByteArray(),
            buildMemberChangeSignature(type, timestamp)
        )
        storage.insertGroupInfoChange(message, closedGroup)
    }

    private fun handleMemberLeft(message: GroupUpdated, closedGroup: AccountId) {
        scope.launch {
            try {
                groupManagerV2.handleMemberLeftMessage(
                    AccountId(message.sender!!), closedGroup
                )
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle member left message", e)
            }
        }
    }

    private fun handleMemberLeftNotification(message: GroupUpdated, closedGroup: AccountId) {
        storage.insertGroupInfoChange(message, closedGroup)
    }

    private fun handleDeleteMemberContent(message: GroupUpdated, closedGroup: AccountId) {
        val deleteMemberContent = message.inner.deleteMemberContent
        val adminSig = if (deleteMemberContent.hasAdminSignature()) deleteMemberContent.adminSignature.toByteArray()!! else byteArrayOf()

        val hasValidAdminSignature = adminSig.isNotEmpty() && runCatching {
            verifyAdminSignature(
                closedGroup,
                adminSig,
                buildDeleteMemberContentSignature(
                    memberIds = deleteMemberContent.memberSessionIdsList.asSequence().map(::AccountId).asIterable(),
                    messageHashes = deleteMemberContent.messageHashesList,
                    timestamp = message.sentTimestamp!!,
                )
            )
        }.isSuccess

        scope.launch {
            try {
                groupManagerV2.handleDeleteMemberContent(
                    groupId = closedGroup,
                    deleteMemberContent = deleteMemberContent,
                    timestamp = message.sentTimestamp!!,
                    sender = AccountId(message.sender!!),
                    senderIsVerifiedAdmin = hasValidAdminSignature
                )
            } catch (e: Exception) {
                Log.e("GroupUpdated", "Failed to handle delete member content", e)
            }
        }
    }


}
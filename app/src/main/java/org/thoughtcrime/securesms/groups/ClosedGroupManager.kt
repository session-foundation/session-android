package org.thoughtcrime.securesms.groups

import android.content.Context
import network.loki.messenger.libsession_util.ConfigBase
import network.loki.messenger.libsession_util.util.Bytes
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.sending_receiving.notifications.PushRegistryV1
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.GroupUtil
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.dependencies.ConfigFactory

object ClosedGroupManager {

    fun silentlyRemoveGroup(context: Context, threadId: Long, groupPublicKey: String, groupID: String, userPublicKey: String, delete: Boolean = true) {
        val storage = MessagingModuleConfiguration.shared.storage
        // Mark the group as inactive
        storage.setActive(groupID, false)
        storage.removeClosedGroupPublicKey(groupPublicKey)
        // Remove the key pairs
        storage.removeAllClosedGroupEncryptionKeyPairs(groupPublicKey)
        storage.removeMember(groupID, Address.fromSerialized(userPublicKey))
        // Notify the PN server
        PushRegistryV1.unsubscribeGroup(closedGroupPublicKey = groupPublicKey, publicKey = userPublicKey)
        // Stop polling
        storage.cancelPendingMessageSendJobs(threadId)
        ApplicationContext.getInstance(context).messageNotifier.updateNotification(context)
        if (delete) {
            storage.deleteConversation(threadId)
        }
    }

    fun ConfigFactory.updateLegacyGroup(group: GroupRecord) {
        if (!group.isLegacyGroup) return
        val storage = MessagingModuleConfiguration.shared.storage
        val threadId = storage.getThreadId(group.encodedId) ?: return
        val groupPublicKey = GroupUtil.doubleEncodeGroupID(group.getId())
        val latestKeyPair = storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: return

        withMutableUserConfigs {
            val groups = it.userGroups

            val legacyInfo = groups.getOrConstructLegacyGroupInfo(groupPublicKey)
            val latestMemberMap = GroupUtil.createConfigMemberMap(group.members.map(Address::toString), group.admins.map(Address::toString))
            val toSet = legacyInfo.copy(
                members = latestMemberMap,
                name = group.title,
                priority = if (storage.isPinned(threadId)) ConfigBase.PRIORITY_PINNED else ConfigBase.PRIORITY_VISIBLE,
                encPubKey = Bytes((latestKeyPair.publicKey as DjbECPublicKey).publicKey),  // 'serialize()' inserts an extra byte
                encSecKey = Bytes(latestKeyPair.privateKey.serialize())
            )
            groups.set(toSet)
        }
    }
}
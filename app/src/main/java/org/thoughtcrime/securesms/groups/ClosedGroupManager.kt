package org.thoughtcrime.securesms.groups

import android.content.Context
import network.loki.messenger.libsession_util.ConfigBase
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.sending_receiving.notifications.PushRegistryV1
import org.session.libsession.messaging.sending_receiving.pollers.ClosedGroupPollerV2
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
        ClosedGroupPollerV2.shared.stopPolling(groupPublicKey)
        storage.cancelPendingMessageSendJobs(threadId)
        ApplicationContext.getInstance(context).messageNotifier.updateNotification(context)
        if (delete) {
            storage.deleteConversation(threadId)
        }
    }

    fun ConfigFactory.removeLegacyGroup(group: GroupRecord): Boolean {
        val groups = userGroups ?: return false
        if (!group.isClosedGroup) return false
        val groupPublicKey = GroupUtil.doubleEncodeGroupID(group.getId())
        return groups.eraseLegacyGroup(groupPublicKey)
    }

    fun ConfigFactory.updateLegacyGroup(group: GroupRecord) {
        val groups = userGroups ?: return
        if (!group.isClosedGroup) return
        val storage = MessagingModuleConfiguration.shared.storage
        val threadId = storage.getThreadId(group.encodedId) ?: return
        val groupPublicKey = GroupUtil.doubleEncodeGroupID(group.getId())
        val latestKeyPair = storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: return
        val legacyInfo = groups.getOrConstructLegacyGroupInfo(groupPublicKey)
        val latestMemberMap = GroupUtil.createConfigMemberMap(group.members.map(Address::serialize), group.admins.map(Address::serialize))
        val toSet = legacyInfo.copy(
            members = latestMemberMap,
            name = group.title,
            priority = if (storage.isPinned(threadId)) ConfigBase.PRIORITY_PINNED else ConfigBase.PRIORITY_VISIBLE,
            encPubKey = (latestKeyPair.publicKey as DjbECPublicKey).publicKey,  // 'serialize()' inserts an extra byte
            encSecKey = latestKeyPair.privateKey.serialize()
        )
        groups.set(toSet)
    }

}
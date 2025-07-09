package org.thoughtcrime.securesms.groups

import android.content.Context
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.sending_receiving.notifications.PushRegistryV1
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.ApplicationContext

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

}
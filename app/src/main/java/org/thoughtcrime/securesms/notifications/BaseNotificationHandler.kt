package org.thoughtcrime.securesms.notifications

import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.ThreadId
import org.thoughtcrime.securesms.database.getAddressAndLastSeen
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.notifications.ThreadBasedNotificationHandler.Companion.currentlyShowingConversation
import org.thoughtcrime.securesms.util.CurrentActivityObserver

abstract class BaseNotificationHandler(
    private val currentActivityObserver: CurrentActivityObserver,
    protected val threadDb: ThreadDatabase,
    protected val recipientRepository: RecipientRepository,
) {
    protected val currentActivity get() = currentActivityObserver.currentActivity.value
    protected val currentlyShowingConversation: Address.Conversable?
        get() = currentActivityObserver.currentlyShowingConversation

    protected data class ThreadData(
        val address: Address.Conversable,
        val lastSeen: Long,
        val notifyType: NotifyType,
        val recipient: Recipient,
    )

    protected fun getThreadDataIfEligibleForNotification(id: ThreadId): ThreadData? {
        if (currentActivity is HomeActivity) return null

        val (threadAddress, lastSeen) = threadDb.getAddressAndLastSeen(id) ?: run {
            Log.w("BaseNotificationHandlerø", "Unable to get address for threadId=$id")
            return null
        }

        if (currentActivityObserver.currentlyShowingConversation == threadAddress) {
            return null
        }

        val threadRecipient = recipientRepository.getRecipientSync(threadAddress)
        if (threadRecipient.blocked) return null

        val threadNotifyType = threadRecipient.effectiveNotifyType()
        if (threadNotifyType == NotifyType.NONE) return null

        return ThreadData(threadAddress, lastSeen, threadNotifyType, threadRecipient)
    }
}
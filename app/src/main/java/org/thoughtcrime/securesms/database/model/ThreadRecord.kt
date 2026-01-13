package org.thoughtcrime.securesms.database.model

import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData

data class ThreadRecord(
    val lastMessage: MessageRecord?,
    val threadId: Long,
    val recipient: Recipient,
    val count: Int, // total message count
    val unreadCount: Int, // unread message count
    val unreadMentionCount: Int, // unread mention count
    val date: Long,
    val isUnread: Boolean,
    val invitingAdminId: String?,
) {
    val isDelivered: Boolean
        get() = lastMessage?.isDelivered == true

    val isFailed: Boolean
        get() = lastMessage?.isFailed == true

    val isSent: Boolean
        get() = lastMessage?.isSent == true

    val isPending: Boolean
        get() = lastMessage?.isPending == true

    val isPinned: Boolean
        get() = recipient.isPinned

    val isRead: Boolean
        get() = lastMessage?.isRead == true

    val isOutgoing: Boolean
        get() = lastMessage?.isOutgoing == true

    val groupThreadStatus: GroupThreadStatus
        get() {
            val group = recipient.data as? RecipientData.Group

            return when {
                group?.kicked == true -> GroupThreadStatus.Kicked
                group?.destroyed == true -> GroupThreadStatus.Destroyed
                else -> GroupThreadStatus.None
            }
        }
}

package org.thoughtcrime.securesms.util

import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.messages.SignalServiceDataMessage

object SessionMetaProtocol {

    private val timestamps = mutableSetOf<Long>()

    fun getTimestamps(): Set<Long> {
        return timestamps
    }

    fun addTimestamp(timestamp: Long) {
        timestamps.add(timestamp)
    }

    @JvmStatic
    fun clearReceivedMessages() {
        timestamps.clear()
    }

    fun removeTimestamps(timestamps: Set<Long>) {
        SessionMetaProtocol.timestamps.removeAll(timestamps)
    }

    @JvmStatic
    fun shouldIgnoreMessage(timestamp: Long): Boolean {
        val shouldIgnoreMessage = timestamps.contains(timestamp)
        timestamps.add(timestamp)
        return shouldIgnoreMessage
    }

    @JvmStatic
    fun canUserReplyToNotification(recipient: Recipient): Boolean {
        // TODO return !recipient.address.isRSSFeed
        return true
    }

    @JvmStatic
    fun shouldSendDeliveryReceipt(message: SignalServiceDataMessage, address: Address): Boolean {
        if (address.isGroup) { return false }
        val hasBody = message.body.isPresent && message.body.get().isNotEmpty()
        val hasAttachment = message.attachments.isPresent && message.attachments.get().isNotEmpty()
        val hasLinkPreview = message.previews.isPresent && message.previews.get().isNotEmpty()
        return hasBody || hasAttachment || hasLinkPreview
    }

    @JvmStatic
    fun shouldSendReadReceipt(recipient: Recipient): Boolean {
        return !recipient.isGroupRecipient && recipient.isApproved && !recipient.isBlocked
    }

    @JvmStatic
    fun shouldSendTypingIndicator(recipient: Recipient): Boolean {
        return !recipient.isGroupRecipient && recipient.isApproved && !recipient.isBlocked
    }
}
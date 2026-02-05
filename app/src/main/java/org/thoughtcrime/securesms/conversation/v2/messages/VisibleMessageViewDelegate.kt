package org.thoughtcrime.securesms.conversation.v2.messages

import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.model.MessageId

interface VisibleMessageViewDelegate {
    fun gotoMessageByTimestamp(timestamp: Long, smoothScroll: Boolean, highlight: Boolean)
    fun onReactionClicked(emoji: String, messageId: MessageId, userWasSender: Boolean)
    fun onReactionLongClicked(messageId: MessageId, emoji: String?)
    fun showUserProfileModal(recipient: Recipient)
}
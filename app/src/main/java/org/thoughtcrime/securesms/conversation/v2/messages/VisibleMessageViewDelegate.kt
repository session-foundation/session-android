package org.thoughtcrime.securesms.conversation.v2.messages

import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.model.MessageId

interface VisibleMessageViewDelegate {
    fun playVoiceMessageAtIndexIfPossible(indexInAdapter: Int)
    fun highlightMessageFromTimestamp(timestamp: Long)
    fun onReactionClicked(emoji: String, messageId: MessageId, userWasSender: Boolean)
    fun onReactionLongClicked(messageId: MessageId, emoji: String?)
    fun showUserProfileModal(recipient: Recipient)
}
package org.thoughtcrime.securesms.search.model

import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.model.MessageId

data class MessageResult(
    val messageId: MessageId,
    val conversationRecipient: Recipient,
    val messageRecipient: Recipient,
    val bodySnippet: String,
    val threadId: Long,
    val sentTimestampMs: Long
)

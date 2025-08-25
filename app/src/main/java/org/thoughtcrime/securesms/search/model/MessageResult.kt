package org.thoughtcrime.securesms.search.model

import org.session.libsession.utilities.recipients.Recipient

data class MessageResult(
    val conversationRecipient: Recipient,
    val messageRecipient: Recipient,
    val bodySnippet: String,
    val threadId: Long,
    val sentTimestampMs: Long
)

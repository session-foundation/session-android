package org.session.libsession.messaging.messages

import org.thoughtcrime.securesms.database.model.MessageId

data class MarkAsDeletedMessage(
    val messageId: MessageId,
    val isOutgoing: Boolean
)

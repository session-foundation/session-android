package org.thoughtcrime.securesms.database.model

import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.mms.SlideDeck

data class Quote(
    val id: Long,
    val quoteMessageId: MessageId?, //todo convov3 maybe I can remove the id and isOriginalMissing and rely on messageId only
    val author: Recipient,
    val text: String?,
    val isOriginalMissing: Boolean,
    val attachment: SlideDeck
) {
    val quoteModel: QuoteModel
        get() = QuoteModel(
            id = id,
            author = author.address,
            text = text,
            missing = this.isOriginalMissing,
            attachments = attachment.asAttachments()
        )
}

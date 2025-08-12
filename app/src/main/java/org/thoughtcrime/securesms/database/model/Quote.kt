package org.thoughtcrime.securesms.database.model

import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.mms.SlideDeck

data class Quote(
    val id: Long,
    val author: Address,
    val text: String?,
    val isOriginalMissing: Boolean,
    val attachment: SlideDeck
) {
    val quoteModel: QuoteModel
        get() = QuoteModel(id, author, text, this.isOriginalMissing, attachment.asAttachments())
}

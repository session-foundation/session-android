package org.session.libsession.messaging.messages.signal

import network.loki.messenger.libsession_util.protocol.ProFeatures
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.model.content.MessageContent

class IncomingMediaMessage(
    val from: Address,
    val sentTimeMillis: Long,
    val expiresIn: Long,
    val expireStartedAt: Long,
    val isMessageRequestResponse: Boolean,
    val hasMention: Boolean,
    val body: String?,
    val group: Address.GroupLike?,
    val attachments: List<Attachment>,
    val proFeatures: ProFeatures,
    val messageContent: MessageContent?,
    val quote: QuoteModel?,
    val linkPreviews: List<LinkPreview>,
    val dataExtractionNotification: DataExtractionNotificationInfoMessage?,
) {

    constructor(
        message: VisibleMessage,
        from: Address,
        expiresIn: Long,
        expireStartedAt: Long,
        group: Address.GroupLike?,
        attachments: List<Attachment>,
        quote: QuoteModel?,
        linkPreviews: List<LinkPreview>
    ): this(
        from = from,
        sentTimeMillis = message.sentTimestamp!!,
        expiresIn = expiresIn,
        expireStartedAt = expireStartedAt,
        isMessageRequestResponse = false,
        hasMention = message.hasMention,
        body = message.text,
        group = group,
        attachments = attachments,
        proFeatures = message.proFeatures,
        messageContent = null,
        quote = quote,
        linkPreviews = linkPreviews,
        dataExtractionNotification = null
    )

    val isMediaSavedDataExtraction: Boolean get() =
        dataExtractionNotification?.kind == DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED
}
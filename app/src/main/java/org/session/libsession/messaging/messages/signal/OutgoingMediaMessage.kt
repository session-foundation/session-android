package org.session.libsession.messaging.messages.signal

import network.loki.messenger.libsession_util.protocol.ProMessageFeature
import network.loki.messenger.libsession_util.util.BitSet
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.model.content.MessageContent

class OutgoingMediaMessage(
    val recipient: Address,
    val body: String?,
    val attachments: List<Attachment>,
    val sentTimeMillis: Long,
    val expiresInMillis: Long,
    val expireStartedAtMillis: Long,
    val outgoingQuote: QuoteModel?,
    val messageContent: MessageContent?,
    val linkPreviews: List<LinkPreview>,
    val group: Address.GroupLike?,
    val isGroupUpdateMessage: Boolean,
    val proFeatures: BitSet<ProMessageFeature> = BitSet()
) {
    init {
        check(!isGroupUpdateMessage || group != null) {
            "Group update messages must have a group address"
        }
    }

    constructor(
        message: VisibleMessage,
        recipient: Address,
        attachments: List<Attachment>,
        outgoingQuote: QuoteModel?,
        linkPreview: LinkPreview?,
        expiresInMillis: Long,
        expireStartedAt: Long
    ) : this(
        recipient = recipient,
        body = message.text,
        attachments = attachments,
        sentTimeMillis = message.sentTimestamp!!,
        expiresInMillis = expiresInMillis,
        expireStartedAtMillis = expireStartedAt,
        outgoingQuote = outgoingQuote,
        messageContent = null,
        linkPreviews = linkPreview?.let { listOf(it) } ?: emptyList(),
        group = null,
        isGroupUpdateMessage = false,
    )

    constructor(
        recipient: Address,
        body: String?,
        group: Address.GroupLike,
        avatar: Attachment?,
        sentTimeMillis: Long,
        expiresInMillis: Long,
        expireStartedAtMillis: Long,
        isGroupUpdateMessage: Boolean,
        quote: QuoteModel?,
        previews: List<LinkPreview>,
        messageContent: MessageContent?,
    ) : this(
        recipient = recipient,
        body = body,
        attachments = avatar?.let { listOf(it) } ?: emptyList(),
        sentTimeMillis = sentTimeMillis,
        expiresInMillis = expiresInMillis,
        expireStartedAtMillis = expireStartedAtMillis,
        outgoingQuote = quote,
        messageContent = messageContent,
        linkPreviews = previews,
        group = group,
        isGroupUpdateMessage = isGroupUpdateMessage,
    )

    // legacy code
    val isSecure: Boolean get() = true

    val isGroup: Boolean get() = group != null
}

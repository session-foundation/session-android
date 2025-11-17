package org.session.libsession.messaging.messages.signal

import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Contact
import org.session.libsession.utilities.DistributionTypes
import org.session.libsession.utilities.IdentityKeyMismatch
import org.session.libsession.utilities.NetworkFailure
import org.thoughtcrime.securesms.database.model.content.MessageContent

class OutgoingMediaMessage(
    val recipient: Address,
    val body: String?,
    val attachments: List<Attachment>,
    val sentTimeMillis: Long,
    val distributionType: Int,
    val subscriptionId: Int,
    val expiresInMillis: Long,
    val expireStartedAtMillis: Long,
    val outgoingQuote: QuoteModel?,
    val messageContent: MessageContent?,
    val networkFailures: List<NetworkFailure>,
    val identityKeyMismatches: List<IdentityKeyMismatch>,
    val contacts: List<Contact>,
    val linkPreviews: List<LinkPreview>,
    val group: Address.GroupLike?,
    val isGroupUpdateMessage: Boolean,
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
        subscriptionId = -1,
        expiresInMillis = expiresInMillis,
        expireStartedAtMillis = expireStartedAt,
        distributionType = DistributionTypes.DEFAULT,
        outgoingQuote = outgoingQuote,
        contacts = emptyList(),
        messageContent = null,
        linkPreviews = linkPreview?.let { listOf(it) } ?: emptyList(),
        networkFailures = emptyList(),
        identityKeyMismatches = emptyList(),
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
        contacts: List<Contact>,
        previews: List<LinkPreview>,
        messageContent: MessageContent?,
    ) : this(
        recipient = recipient,
        body = body,
        attachments = avatar?.let { listOf(it) } ?: emptyList(),
        sentTimeMillis = sentTimeMillis,
        distributionType = DistributionTypes.CONVERSATION,
        subscriptionId = -1,
        expiresInMillis = expiresInMillis,
        expireStartedAtMillis = expireStartedAtMillis,
        outgoingQuote = quote,
        messageContent = messageContent,
        networkFailures = emptyList(),
        identityKeyMismatches = emptyList(),
        contacts = contacts,
        linkPreviews = previews,
        group = group,
        isGroupUpdateMessage = isGroupUpdateMessage,
    )

    // legacy code
    val isSecure: Boolean get() = true

    val isGroup: Boolean get() = group != null
}

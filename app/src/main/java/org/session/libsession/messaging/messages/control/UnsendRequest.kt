package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.copyExpiration
import org.session.libsignal.protos.SignalServiceProtos

class UnsendRequest(var timestamp: Long? = null, var author: String? = null): ControlMessage() {

    override val isSelfSendValid: Boolean = true

    override fun shouldDiscardIfBlocked(): Boolean = true // current behavior, not sure if should be true

    // region Validation
    override fun isValid(): Boolean {
        if (!super.isValid()) return false
        return timestamp != null && author != null
    }
    // endregion

    companion object {
        const val TAG = "UnsendRequest"

        fun fromProto(proto: SignalServiceProtos.Content): UnsendRequest? =
            proto.takeIf { it.hasUnsendRequest() }?.unsendRequest?.run { UnsendRequest(timestampMs, author) }?.copyExpiration(proto)
    }

    protected override fun buildProto(
        builder: SignalServiceProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        builder.unsendRequestBuilder
            .setTimestampMs(timestamp!!)
            .setAuthor(author!!)
    }

}
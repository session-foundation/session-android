package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.copyExpiration
import org.session.protos.SessionProtos

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

        fun fromProto(proto: SessionProtos.Content): UnsendRequest? =
            proto.takeIf { it.hasUnsendRequest() }?.unsendRequest?.run { UnsendRequest(timestamp, author) }?.copyExpiration(proto)
    }

    override fun buildProto(
        builder: SessionProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        builder.unsendRequestBuilder
            .setTimestamp(timestamp!!)
            .setAuthor(author!!)
    }

}
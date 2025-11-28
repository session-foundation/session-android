package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.copyExpiration
import org.session.libsignal.protos.SignalServiceProtos

class MessageRequestResponse(val isApproved: Boolean) : ControlMessage() {

    override val isSelfSendValid: Boolean = true

    override fun shouldDiscardIfBlocked(): Boolean = true

    override fun buildProto(
        builder: SignalServiceProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        builder.messageRequestResponseBuilder
            .setIsApproved(isApproved)
    }

    companion object {
        const val TAG = "MessageRequestResponse"

        fun fromProto(proto: SignalServiceProtos.Content): MessageRequestResponse? {
            val messageRequestResponseProto = if (proto.hasMessageRequestResponse()) proto.messageRequestResponse else return null
            val isApproved = messageRequestResponseProto.isApproved
            return MessageRequestResponse(isApproved)
                    .copyExpiration(proto)
        }
    }
}

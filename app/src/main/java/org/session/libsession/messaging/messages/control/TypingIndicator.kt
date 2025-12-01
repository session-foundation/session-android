package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.copyExpiration
import org.session.protos.SessionProtos

class TypingIndicator() : ControlMessage() {
    var kind: Kind? = null

    override val defaultTtl: Long = 20 * 1000

    override fun isValid(): Boolean {
        if (!super.isValid()) return false
        return kind != null
    }

    override fun shouldDiscardIfBlocked(): Boolean = true

    companion object {
        const val TAG = "TypingIndicator"

        fun fromProto(proto: SessionProtos.Content): TypingIndicator? {
            val typingIndicatorProto = if (proto.hasTypingMessage()) proto.typingMessage else return null
            val kind = Kind.fromProto(typingIndicatorProto.action)
            return TypingIndicator(kind = kind)
                    .copyExpiration(proto)
        }
    }

    enum class Kind {
        STARTED, STOPPED;

        companion object {
            @JvmStatic
            fun fromProto(proto: SessionProtos.TypingMessage.Action): Kind =
                when (proto) {
                    SessionProtos.TypingMessage.Action.STARTED -> STARTED
                    SessionProtos.TypingMessage.Action.STOPPED -> STOPPED
                }
        }

        fun toProto(): SessionProtos.TypingMessage.Action {
            when (this) {
                STARTED -> return SessionProtos.TypingMessage.Action.STARTED
                STOPPED -> return SessionProtos.TypingMessage.Action.STOPPED
            }
        }
    }

    internal constructor(kind: Kind) : this() {
        this.kind = kind
    }

    override fun buildProto(
        builder: SessionProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        builder.typingMessageBuilder
            .setTimestamp(sentTimestamp!!)
            .setAction(kind!!.toProto())
    }
}
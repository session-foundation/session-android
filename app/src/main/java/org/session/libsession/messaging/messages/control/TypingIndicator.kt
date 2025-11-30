package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.copyExpiration
import org.session.libsignal.protos.SignalServiceProtos

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

        fun fromProto(proto: SignalServiceProtos.Content): TypingIndicator? {
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
            fun fromProto(proto: SignalServiceProtos.TypingMessage.Action): Kind =
                when (proto) {
                    SignalServiceProtos.TypingMessage.Action.STARTED -> STARTED
                    SignalServiceProtos.TypingMessage.Action.STOPPED -> STOPPED
                }
        }

        fun toProto(): SignalServiceProtos.TypingMessage.Action {
            when (this) {
                STARTED -> return SignalServiceProtos.TypingMessage.Action.STARTED
                STOPPED -> return SignalServiceProtos.TypingMessage.Action.STOPPED
            }
        }
    }

    internal constructor(kind: Kind) : this() {
        this.kind = kind
    }

    protected override fun buildProto(
        builder: SignalServiceProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        builder.typingMessageBuilder
            .setTimestampMs(sentTimestamp!!)
            .setAction(kind!!.toProto())
    }
}
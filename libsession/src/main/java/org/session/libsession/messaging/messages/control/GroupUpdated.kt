package org.session.libsession.messaging.messages.control

import org.session.libsignal.protos.SignalServiceProtos.Content
import org.session.libsignal.protos.SignalServiceProtos.DataMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.LokiProfile

class GroupUpdated @JvmOverloads constructor(
    val inner: GroupUpdateMessage = GroupUpdateMessage.getDefaultInstance(),
    val profile: LokiProfile? = null
): ControlMessage() {

    override fun isValid(): Boolean {
        return true // TODO: add the validation here
    }

    override val isSelfSendValid: Boolean = true

    override fun shouldDiscardIfBlocked(): Boolean =
        !inner.hasPromoteMessage() && !inner.hasInfoChangeMessage()
                && !inner.hasMemberChangeMessage() && !inner.hasMemberLeftMessage()
                && !inner.hasInviteResponse() && !inner.hasDeleteMemberContent()

    companion object {
        fun fromProto(message: Content): GroupUpdated? =
            if (message.hasDataMessage() && message.dataMessage.hasGroupUpdateMessage())
                GroupUpdated(
                    inner = message.dataMessage.groupUpdateMessage,
                    profile = if (message.dataMessage.hasProfile()) {
                        message.dataMessage.profile
                    } else {
                        null
                    }
                )
            else null
    }

    override fun toProto(): Content {
        val dataMessage = DataMessage.newBuilder()
            .setGroupUpdateMessage(inner)
            .build()
        return Content.newBuilder()
            .setDataMessage(dataMessage)
            .build()
    }
}
package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.libsignal.protos.SignalServiceProtos.Content
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage

class GroupUpdated @JvmOverloads constructor(
    val inner: GroupUpdateMessage = GroupUpdateMessage.getDefaultInstance(),
): ControlMessage() {

    override fun isValid(): Boolean {
        return true
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
                )
            else null
    }

    override fun buildProto(builder: Content.Builder, messageDataProvider: MessageDataProvider) {
        builder.dataMessageBuilder
            .setGroupUpdateMessage(inner)
            .apply { profile?.let(this::setProfile) }
    }
}
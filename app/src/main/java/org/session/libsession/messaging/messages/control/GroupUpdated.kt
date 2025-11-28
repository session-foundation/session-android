package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.protos.SessionProtos

class GroupUpdated @JvmOverloads constructor(
    val inner: SessionProtos.GroupUpdateMessage = SessionProtos.GroupUpdateMessage.getDefaultInstance(),
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
        fun fromProto(message: SessionProtos.Content): GroupUpdated? =
            if (message.hasDataMessage() && message.dataMessage.hasGroupUpdateMessage())
                GroupUpdated(
                    inner = message.dataMessage.groupUpdateMessage,
                )
            else null
    }

    override fun buildProto(builder: SessionProtos.Content.Builder, messageDataProvider: MessageDataProvider) {
        builder.dataMessageBuilder
            .setGroupUpdateMessage(inner)
            .apply { profile?.let(this::setProfile) }
    }
}
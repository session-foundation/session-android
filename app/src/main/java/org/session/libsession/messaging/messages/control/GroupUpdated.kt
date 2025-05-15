package org.session.libsession.messaging.messages.control

import org.session.libsession.messaging.messages.visible.Profile
import org.session.libsignal.protos.SignalServiceProtos.Content
import org.session.libsignal.protos.SignalServiceProtos.DataMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage

class GroupUpdated @JvmOverloads constructor(
    val inner: GroupUpdateMessage = GroupUpdateMessage.getDefaultInstance(),
    val profile: Profile? = null
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
                    profile = Profile.fromProto(message.dataMessage)
                )
            else null
    }

    override fun toProto(): Content {
        val dataMessage = DataMessage.newBuilder()
            .setGroupUpdateMessage(inner)
            .apply { profile?.let(this::setProfile) }
            .build()
        return Content.newBuilder()
            .setDataMessage(dataMessage)
            .build()
    }
}
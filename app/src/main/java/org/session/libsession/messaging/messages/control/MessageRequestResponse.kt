package org.session.libsession.messaging.messages.control

import com.google.protobuf.ByteString
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.copyExpiration
import org.session.libsession.messaging.messages.visible.Profile
import org.session.libsignal.protos.SignalServiceProtos

class MessageRequestResponse(val isApproved: Boolean, var profile: Profile? = null) : ControlMessage() {

    override val isSelfSendValid: Boolean = true

    override fun shouldDiscardIfBlocked(): Boolean = true

    protected override fun buildProto(
        builder: SignalServiceProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        builder.messageRequestResponseBuilder
            .setIsApproved(isApproved)
            .also { builder ->
                profile?.profileKey?.let { builder.setProfileKey(ByteString.copyFrom(it)) }
            }
            .profileBuilder
            .also { builder ->
                profile?.displayName?.let { builder.displayName = it }
                profile?.profilePictureURL?.let { builder.profilePicture = it }
            }
    }

    companion object {
        const val TAG = "MessageRequestResponse"

        fun fromProto(proto: SignalServiceProtos.Content): MessageRequestResponse? {
            val messageRequestResponseProto = if (proto.hasMessageRequestResponse()) proto.messageRequestResponse else return null
            val isApproved = messageRequestResponseProto.isApproved
            val profileProto = messageRequestResponseProto.profile
            val profile = Profile().apply {
                displayName = profileProto.displayName
                profileKey = if (messageRequestResponseProto.hasProfileKey()) messageRequestResponseProto.profileKey.toByteArray() else null
                profilePictureURL = profileProto.profilePicture
            }
            return MessageRequestResponse(isApproved, profile)
                    .copyExpiration(proto)
        }
    }
}

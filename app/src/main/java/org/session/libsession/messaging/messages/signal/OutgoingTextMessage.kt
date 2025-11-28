package org.session.libsession.messaging.messages.signal

import network.loki.messenger.libsession_util.protocol.ProFeature
import org.session.libsession.messaging.messages.visible.OpenGroupInvitation
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.Address

data class OutgoingTextMessage(
    val recipient: Address,
    val message: String?,
    val expiresInMillis: Long,
    val expireStartedAtMillis: Long,
    val sentTimestampMillis: Long,
    val isOpenGroupInvitation: Boolean,
    val proFeatures: Set<ProFeature> = emptySet()
) {
    constructor(
        message: VisibleMessage,
        recipient: Address,
        expiresInMillis: Long,
        expireStartedAtMillis: Long,
    ): this(
        recipient = recipient,
        message = message.text,
        expiresInMillis = expiresInMillis,
        expireStartedAtMillis = expireStartedAtMillis,
        sentTimestampMillis = message.sentTimestamp!!,
        isOpenGroupInvitation = false,
    )

    companion object {
        fun fromOpenGroupInvitation(
            invitation: OpenGroupInvitation,
            recipient: Address,
            sentTimestampMillis: Long,
            expiresInMillis: Long,
            expireStartedAtMillis: Long,
        ): OutgoingTextMessage? {
            return OutgoingTextMessage(
                recipient = recipient,
                message = UpdateMessageData.buildOpenGroupInvitation(
                    url = invitation.url ?: return null,
                    name = invitation.name ?: return null,
                ).toJSON(),
                expiresInMillis = expiresInMillis,
                expireStartedAtMillis = expireStartedAtMillis,
                sentTimestampMillis = sentTimestampMillis,
                isOpenGroupInvitation = true,
            )
        }
    }
}

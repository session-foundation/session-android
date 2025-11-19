package org.session.libsession.messaging.messages.signal

import network.loki.messenger.libsession_util.protocol.ProFeatures
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.messages.visible.OpenGroupInvitation
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.Address
import java.util.EnumSet

data class IncomingTextMessage(
    val message: String?,
    val sender: Address,
    val sentTimestampMillis: Long,
    val group: Address.GroupLike?,
    val push: Boolean,
    val expiresInMillis: Long,
    val expireStartedAt: Long,
    val callType: Int,
    val hasMention: Boolean,
    val isOpenGroupInvitation: Boolean,
    val isSecureMessage: Boolean,
    val proFeatures: ProFeatures,
    val isGroupMessage: Boolean = false,
    val isGroupUpdateMessage: Boolean = false,
) {
    val callMessageType: CallMessageType? get() =
        CallMessageType.entries.getOrNull(callType)

    val isUnreadCallMessage: Boolean
        get() = callMessageType in EnumSet.of(
            CallMessageType.CALL_MISSED,
            CallMessageType.CALL_FIRST_MISSED,
        )

    val proFeaturesRawValue: Long get() = proFeatures.rawValue

    init {
        check(!isGroupUpdateMessage || isGroupMessage) {
            "A message cannot be a group update message if it is not a group message"
        }
    }

    constructor(
        message: VisibleMessage,
        sender: Address,
        group: Address.GroupLike?,
        expiresInMillis: Long,
        expireStartedAt: Long,
    ): this(
        message = message.text,
        sender = sender,
        sentTimestampMillis = message.sentTimestamp!!,
        group = group,
        push = true,
        expiresInMillis = expiresInMillis,
        expireStartedAt = expireStartedAt,
        callType = -1,
        hasMention = message.hasMention,
        isOpenGroupInvitation = false,
        isSecureMessage = false,
        proFeatures = message.proFeatures,
    )
    constructor(
        callMessageType: CallMessageType,
        sender: Address,
        group: Address.GroupLike?,
        sentTimestampMillis: Long,
        expiresInMillis: Long,
        expireStartedAt: Long,
    ): this(
        message = null,
        sender = sender,
        sentTimestampMillis = sentTimestampMillis,
        group = group,
        push = false,
        expiresInMillis = expiresInMillis,
        expireStartedAt = expireStartedAt,
        callType = callMessageType.ordinal,
        hasMention = false,
        isOpenGroupInvitation = false,
        isSecureMessage = false,
        proFeatures = ProFeatures.NONE,
    )

    companion object {
        fun fromOpenGroupInvitation(
            invitation: OpenGroupInvitation,
            sender: Address,
            sentTimestampMillis: Long,
            expiresInMillis: Long,
            expireStartedAt: Long,
        ): IncomingTextMessage? {
            val body = UpdateMessageData.buildOpenGroupInvitation(
                url = invitation.url ?: return null,
                name = invitation.name ?: return null,
            ).toJSON()

            return IncomingTextMessage(
                message = body,
                sender = sender,
                sentTimestampMillis = sentTimestampMillis,
                group = null,
                push = true,
                expiresInMillis = expiresInMillis,
                expireStartedAt = expireStartedAt,
                callType = -1,
                hasMention = false,
                isOpenGroupInvitation = true,
                isSecureMessage = false,
                proFeatures = ProFeatures.NONE,
            )
        }
    }
}